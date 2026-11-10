#!/system/bin/sh
# 手机端一键字幕：媒体文件 -> 16k wav -> 切段 + SenseVoice -> Hy-MT2 翻译 -> SRT+LRC
#
# 用法（在手机上）：
#     sh run.sh <输入文件> [输出.srt]（产物成对：<同名>.chs.srt 与 <同名>.chs.lrc）
#
# 依赖（可执行文件在 nativeLibraryDir 里，改名成 libXXX.so 才能 exec）：
#     libffmpeg.so  libsherpaoffline.so  libasmrmobile.so  libllamaserver.so
#     + libonnxruntime.so / libsherpa-onnx-c-api.so / libsherpa-onnx-cxx-api.so
#     + 模型 model.int8.onnx / hy.gguf，数据文件 tokens.txt
#
# 为什么按 20 秒切段再识别（重要，别再改回整段 VAD）：
#   ASMR 音声里有大量喘息、耳语、环境音，Silero VAD 把它们全判成语音，
#   整段 VAD 会「一直不断句」。实测 1.5 小时素材无论阈值 0.5/0.6/0.7、
#   min-silence 0.1/0.2、max-speech 5/10，都只切出 45~55 段，
#   字幕直接漏掉大段内容。切成固定 20 秒小段后，每段都有输出，不再漏。
#   多文件一次交给 sherpa-onnx-offline，模型只加载一次（省 1.6s × 段数）。
#
# 其他实测约束：
#   · 只绑 4 个大核（taskset 0F0）—— 全 8 核会把小核拖进来，慢 27 倍
#   · llama-server 没起会自动起一次，起过就不再起
#   · 中间 wav、切段目录、识别文本用完即删（手机上空间宝贵）

# 可执行文件在 nativeLibraryDir（APK 安装后才有 exec 权限）；数据文件在工作目录
D="${ASMR_LIB:-$(cd "$(dirname "$0")" && pwd)}"
W="${ASMR_WORK:-$D}"
LLAMA_DIR="$W"
BIN_FFMPEG="$D/libffmpeg.so"
BIN_SHERPA="$D/libsherpaoffline.so"
BIN_MT="$D/libasmrmobile.so"
BIN_LLAMA="$D/libllamaserver.so"
PORT=18081
SEG_SEC=20

# ── 段首前置与重叠窗口（LEAD / 窗口长度）───────────────────────
# ★ 为什么需要前置（实测，别删）：
#   日语 zipformer-ja 是 RNN-T，在没有任何上文的「冷启动」段里会整句丢失段首。
#   20 秒硬切的 90 秒素材实测 4/5 段中招：_t02 丢「私とお姉」、_t03 丢
#   「との続きを楽しみにしています」、_t04 丢「毎日ご主人様のこと考えていますよ」。
#   把同一段前面补 1.5 秒**真实音频**后全部找回，RTF 不变（0.068 对 0.065）。
#   是缺上文，不是模型缺陷、也不是硬切不好。冷启动不可靠区约 1.0~1.5 秒。
#
# ★ 为什么不能「整段垫一次静音再照原样硬切」（试过，被真机 A/B 否掉，别改回去）：
#   那样只等于把切点整体前移 1.5 秒，**只有第 0 段真的拿到了前置**，
#   第 1 段以后段首依旧是零上下文冷启动，只是把丢词的位置从 40 秒挪到了 60 秒。
#   实测：旧方案丢「お姉」（40 秒边界），垫静音方案丢「アニメか」（60 秒边界）。
#   对照实验（同素材同模型）：real[38.5,60] 认出「私とお姉ちゃんは…」，
#   real[40,60] 的开头却是「ちゃんは…」——丢掉的就是没有前置的那 1.5 秒。
#
# 做法：**逐窗口取 23 秒**，每个窗口两侧都留 1.5 秒余量，谁也不截断谁：
#   窗口 0 = 1.5s 静音 ++ 真实 [0, 21.5]        长度 23.0s
#   窗口 k = 真实 [20k-1.5, 20k+21.5]           长度 23.0s（k>=1）
#   归属：窗口 k 只认 local ts >= 1.5 的 token，即真实 [20k, 20k+20]。
#   开头那 1.5 秒余量是给模型补上下文；结尾那 1.5 秒余量是保证压在边界上的
#   那个字**在窗口里是完整的**（不然会被切成半个字，实测就会丢词）。
#   归属区间首尾相接且互不重叠：[0,20)、[20,40)、[40,60)…
#   换算：真实 = local + (k*SEG_SEC - LEAD)，也就是 asmr_mobile 的 offset；
#   被丢掉的 local < LEAD 恒等于 1.5 秒（第 0 段丢掉的只是垫出来的静音）。
# ★ 下面第 4 步跑 asmr_mobile 时要把它作为 --lead 传回去，两处必须同值；
#   改这里记得同步改；LEAD_MS 恒等于 LEAD_SEC*1000。
# 代价实测：-ss/-t 逐窗口切，0.08 秒/窗口；1.5 小时素材约 270 个窗口 ≈ 22 秒。
LEAD_MS=1500
LEAD_SEC=1.5
# 窗口长度（秒）。mksh 不做浮点，所以用 LEAD_MS 推整数：20 + 2*1500/1000 = 23
LEAD_WIN=$((SEG_SEC + 2 * LEAD_MS / 1000))
# 一个「切满」窗口的字节数：23s × 16000Hz × 2B = 736000，加 78 字节 WAV 头。
# 小于它就说明切到了文件末尾（该窗口即最后一段）；小到 0.1 秒以下则是空窗口。
FULL_WIN=735000

# ── 挑大核（不能硬编码 taskset 0F0）─────────────────────────────
# 0F0 只适合「4 小核在 cpu0-3、4 大核在 cpu4-7」这种布局（天玑 1000+ 就是）。
# 换到 1+3+4（X3 + A715 + A510）、2+2+4、或者 CPU 编号顺序不同的机器上，
# 0F0 可能正好绑到小核上 —— 实测小核跑 llama 比大核慢 27 倍。
# 所以按 cpufreq 的最高频率排序，动态取前 4 个核；读不到频率就不绑核。
pick_big_cores() {
    want=$1
    tmp="$W/_cpu_$$.txt"
    : > "$tmp"
    for c in /sys/devices/system/cpu/cpu[0-9]*; do
        n=${c##*/cpu}
        case "$n" in ''|*[!0-9]*) continue ;; esac
        f=$(cat "$c/cpufreq/cpuinfo_max_freq" 2>/dev/null)
        case "$f" in ''|*[!0-9]*) f=0 ;; esac
        echo "$f $n" >> "$tmp"
    done
    total=$(wc -l < "$tmp" | tr -d ' ')
    case "$total" in ''|*[!0-9]*) total=0 ;; esac
    mask=0
    n_sel=0
    if [ "$total" -gt 0 ]; then
        # 频率降序取前 want 个（sort -rn 在 toybox/busybox 都有）
        for n in $(sort -rn "$tmp" | head -n "$want" | awk '{print $2}'); do
            mask=$((mask | (1 << n)))
            n_sel=$((n_sel + 1))
        done
    fi
    rm -f "$tmp"
    echo "$mask $n_sel"
}

BIG=$(pick_big_cores 4)
CPU_MASK=${BIG%% *}
# 模型计算线程数固定压到 2（2026-11-10）：实测 4 线程连跑多窗会触发
# boot.reason=kernel_panic,oom 内核重启（7.6GB 内存被 zipformer+系统驻留+SwapCached 顶穿）。
# 2 线程单窗即可正常跑（RTF≈0.076），速度损失可接受但稳定得多。不要改回 4。
THREADS=2
case "$THREADS" in ''|*[!0-9]*|0) THREADS=2 ;; esac
[ "$THREADS" -gt 2 ] && THREADS=2
if [ "$CPU_MASK" = "0" ]; then
    TASKSET=""
else
    # toybox 的 taskset 只认纯十六进制（不带 0x），实测 `taskset 0xf0` 报 bad mask
    TASKSET="taskset $(printf '%X' "$CPU_MASK")"
fi

# 推理进程降一级 nice：2 线程绑大核已经避免了抢满 CPU，但系统进程（system_server）
# 还需要预留余量——2026-11-11 实测整机 ANR 是系统侧先倒，留点余量能少犯。
NICE="nice -n 10"
command -v nice > /dev/null 2>&1 || NICE=""

IN="$1"
if [ -z "$IN" ]; then
    echo "用法: sh $0 <输入媒体文件> [输出.srt]"
    exit 2
fi
if [ ! -f "$IN" ]; then
    echo "[错误] 找不到文件: $IN"
    exit 2
fi

BASE=$(basename "$IN")
STEM=${BASE%.*}
OUT="$2"
if [ -z "$OUT" ]; then
    OUT="$(dirname "$IN")/$STEM.chs.srt"
fi

WAV="$W/_asmr_$$.wav"
TXT="$W/_asmr_$$.txt"
SEGDIR="$W/_seg_$$"
# 融合车道：zipformer 主输出、SenseVoice 补输出、待补窗口列表
ZIP_TXT="$W/_asmr_$$_zip.txt"
SV_TXT="$W/_asmr_$$_sv.txt"
DUMB_LIST="$W/_asmr_$$_dumb.txt"
t_start=$(date +%s)

cleanup() {
    # WAV 可能等于用户自己交进来的 .wav，别把他的原文件删了
    if [ -n "$WAV" ] && [ "$WAV" != "$IN" ]; then rm -f "$WAV"; fi
    rm -f "$TXT" "$ZIP_TXT" "$SV_TXT" "$DUMB_LIST"
    rm -rf "$SEGDIR"
}

# 找模型目录：hy.gguf（翻译模型）必需；识别车道可选 zipformer 和 SenseVoice 两套。
#   · <dir>/zipformer-ja/{...}  = zipformer 主车道
#   · <dir>/model.int8.onnx    = SenseVoice 补车道
# 识别策略（zipformer 主、SV 补漏）：
#   两套都在 → 先 zipformer 全片，对它的"哑窗/空窗"再单独补跑 SenseVoice，融合后翻译。
#   只有 zipformer → 单车道（识别可能漏句，无法补）。
#   只有 SenseVoice → 单车道回退（覆盖最广，和图内默认一致）。
# 识别模型放子目录是为了和 SenseVoice 的 tokens.txt 隔离：$W 本身就在候选列表里，
# 而 SenseVoice 的 tokens 固定叫 $W/tokens.txt、zipformer 的也叫 tokens.txt，同名会互相覆盖。
MODEL_DIR=""
ASR_NAME=""
ZIP_DIR=""
ZIP_READY=0
SV_DIR=""
SV_READY=0
SV_TOKENS=""
# 第一优先：App 传进来的私有目录（/data/user/0/com.nihonsubs/files/models）。
# 2026-11-11 加固：模型别放 /sdcard（FUSE），llama mmap 1.1 GB 模型时缺页会把系统拖垮。
for d in $ASMR_MODEL "$W/models" "$W" \
         /sdcard/Android/data/com.nihonsubs/files/models \
         /storage/emulated/0/Android/data/com.nihonsubs/files/models \
         /storage/emulated/0/Download/asmr-models; do
    [ -f "$d/hy.gguf" ] || continue
    [ -n "$MODEL_DIR" ] || MODEL_DIR="$d"
    if [ -f "$d/zipformer-ja/encoder.int8.onnx" ] && [ -f "$d/zipformer-ja/decoder.onnx" ] && \
       [ -f "$d/zipformer-ja/joiner.onnx" ] && [ -f "$d/zipformer-ja/tokens.txt" ] && [ "$ZIP_READY" -eq 0 ]; then
        ZIP_DIR="$d/zipformer-ja"; ZIP_READY=1
        [ -n "$ASR_NAME" ] && ASR_NAME="$ASR_NAME + zipformer-ja"
    fi
    if [ -f "$d/model.int8.onnx" ] && [ "$SV_READY" -eq 0 ]; then
        SV_DIR="$d"; SV_READY=1
        if [ -f "$d/tokens.txt" ]; then SV_TOKENS="$d/tokens.txt"
        elif [ -f "$W/tokens.txt" ]; then SV_TOKENS="$W/tokens.txt"; fi
        [ -n "$ASR_NAME" ] && ASR_NAME="$ASR_NAME + SenseVoice"
    fi
done
if [ -z "$MODEL_DIR" ]; then
    echo "[错误] 找不到模型（必须有 hy.gguf）"
    echo "[错误] 请用 App 里的「导入模型」按钮选 asmr-models.zip"
    exit 1
fi
if [ "$ZIP_READY" -eq 1 ] && [ "$SV_READY" -eq 1 ] && [ -n "$SV_TOKENS" ]; then
    ASR_KIND="fused"
    ASR_NAME="zipformer 主 + SenseVoice 补漏"
elif [ "$ZIP_READY" -eq 1 ]; then
    ASR_KIND="zipformer"
    [ -n "$ASR_NAME" ] || ASR_NAME="日语 zipformer-ja"
elif [ "$SV_READY" -eq 1 ]; then
    ASR_KIND="sensevoice"
    [ -n "$ASR_NAME" ] || ASR_NAME="SenseVoice int8"
else
    echo "[错误] 没有任何识别模型（需要 zipformer-ja/ 或 model.int8.onnx + tokens.txt）"
    echo "[错误] 请用 App 里的「导入模型」按钮选 asmr-models.zip"
    exit 1
fi

case "$IN" in
    *.wav|*.WAV)
        # App 已经用系统 MediaCodec 解好了（16kHz/单声道/16bit），这里直接用
        WAV="$IN"
        echo "[1/4] 提取音频（系统解码，已由 App 完成）"
        echo "      -> $(ls -l "$WAV" | awk '{print $5}') 字节"
        ;;
    *)
        echo "[1/4] 提取音频（ffmpeg 兜底）..."
        "$BIN_FFMPEG" -y -hide_banner -loglevel error -i "$IN" -ar 16000 -ac 1 -c:a pcm_s16le "$WAV" || {
            echo "[错误] ffmpeg 提取失败"
            cleanup
            exit 1
        }
        echo "      -> $(ls -l "$WAV" | awk '{print $5}') 字节"
        ;;
esac

# 翻译服务：没起就起一个（用 -t 4 绑大核）
#
# ★ 进程名的坑（实测踩到，别再改回 pidof llama-server）：
#   可执行文件为了能在 Android 10+ 拿到 exec 权限，改名成了 libllamaserver.so。
#   于是内核里的 comm 是 "libllamaserver."（15 字符截断），cmdline basename 是
#   libllamaserver.so —— 两者都不等于 "llama-server"。
#   实测 pidof llama-server 恒为空 → 旧代码「已有进程就复用」从来没生效过：
#   每个文件都会再 nohup 起一个，新进程 bind 18081 失败退出，
#   白加载一次 1.1GB 模型（30~90 秒），而真正在服务的是上一个老进程。
#   所以判活必须用 llama_pid()，别用 pidof llama-server。
#
# --metrics：打开 /metrics 端点（Prometheus 文本），进度卡的「运行状态」行靠它取实时 tok/s。
# _llama.pid：写**当前真正活着并在服务**的 pid，App 读 /proc/<pid>/stat 算占用几个核。

llama_pid() {
    p=$(pidof libllamaserver.so 2>/dev/null)
    [ -z "$p" ] && p=$(pidof llama-server 2>/dev/null)
    # 多个实例时取第一个（最早起来的那个）
    echo $p | awk '{print $1}'
}

has_metrics() {
    command -v curl > /dev/null 2>&1 || return 1
    curl -s -m 2 "http://127.0.0.1:$PORT/metrics" 2>/dev/null | grep -q prompt_tokens_total
}

# 就绪判据：有 curl 就用 /health（模型没加载完之前根本不监听，判据硬）；
# 没 curl 就退回「grep 日志里的 listening on」—— 不能直接 return 0，
# 那样等待循环会 0 秒 break，服务还没加载完就开始识别。
# 必须是 200 才算就绪：
#   · 连接失败 → curl 退出码非 0，http_code 为空
#   · 模型还在加载 → 有的版本会先 bind 端口再返回 503，此时 curl 退出码仍是 0，
#     所以**不能只看退出码**（实测出现过「等了 1s 就报就绪」的假就绪）
server_alive() {
    if command -v curl > /dev/null 2>&1; then
        [ "$(curl -s -o /dev/null -w '%{http_code}' -m 3 "http://127.0.0.1:$PORT/health" 2>/dev/null)" = "200" ]
    else
        grep -q "listening on" "$LLAMA_DIR/llama.log" 2>/dev/null
    fi
}

start_llama() {
    cd "$LLAMA_DIR" || exit 1
    rm -f "$LLAMA_DIR/llama.log"
    nohup $NICE $TASKSET "$BIN_LLAMA" --model "$MODEL_DIR/hy.gguf" \
        --host 127.0.0.1 --port $PORT -c 4096 -np 1 \
        --no-webui --metrics -t $THREADS \
        > "$LLAMA_DIR/llama.log" 2>&1 < /dev/null &
    # 就绪判据：端口能应答 /health。
    # 不用「grep 日志里的 listening on」—— 那要留日志且不能压 verbosity；
    # /health 在模型加载完之前根本不会监听，判据更硬。
    i=0
    while [ $i -lt 120 ]; do
        if server_alive; then break; fi
        sleep 1
        i=$((i + 1))
    done
    if ! server_alive; then
        # 不能硬往下跑：识别照样出结果，但 [4/4] 每一句翻译都会连不上服务，
        # Rust 侧失败会回退成「原文写进 SRT」，用户拿到的是一堆没翻译的日文字幕。
        echo "[错误] 翻译服务 120 秒内没就绪，中止（日志尾部）："
        tail -5 "$LLAMA_DIR/llama.log" 2>/dev/null
        cleanup
        exit 1
    fi
    echo "      -> 服务就绪（等了 ${i}s）"
}

LP=$(llama_pid)
if [ -n "$LP" ] && server_alive; then
    if has_metrics; then
        echo "[2/4] 翻译服务已在运行（复用，省一次模型加载）"
    elif command -v curl > /dev/null 2>&1; then
        # 老版本起的进程不带 --metrics，App 读不到 tok/s，重启一次
        echo "[2/4] 旧翻译服务不带 metrics，重启中..."
        kill $LP 2>/dev/null
        # 必须等旧进程真的退出再启动，否则新进程 bind 18081 失败就静默退出，
        # 而 _llama.pid 会指向这个已经死掉的新进程（实测踩过）。
        k=0
        while [ $k -lt 15 ]; do
            kill -0 $LP 2>/dev/null || break
            sleep 1
            k=$((k + 1))
        done
        kill -9 $LP 2>/dev/null
        sleep 1
        start_llama
    else
        echo "[2/4] 翻译服务已在运行（无 curl，无法确认 metrics）"
    fi
else
    echo "[2/4] 启动翻译服务（Hy-MT2-1.8B）..."
    start_llama
fi

LP=$(llama_pid)
if [ -n "$LP" ]; then
    echo "$LP" > "$LLAMA_DIR/_llama.pid"
else
    rm -f "$LLAMA_DIR/_llama.pid"
fi

echo "[3/4] 语音识别（${SEG_SEC} 秒一段 + 前后各 ${LEAD_SEC}s 余量 + ${ASR_NAME}）..."
cd "$W" || exit 1
mkdir -p "$SEGDIR" || {
    echo "[错误] 建不了临时目录 $SEGDIR"
    cleanup
    exit 1
}
# 逐窗口取 LEAD_WIN 秒（详见文件头「段首前置与重叠窗口」）。
# 每个窗口一条 ffmpeg、只出一个文件，所以不用 -f segment、也不用先垫出一整份静音文件。
# 窗口 0 用 adelay 在小段前面补 LEAD_MS 静音；k>=1 直接把起点提前 LEAD_MS。
# ★ -ss/-t 都必须放在 -i 前面（输入侧 seek + 截断），这是实测最快的写法（0.08s/窗口）。
# ★ 文件名必须零填充：后面 set -- "$SEGDIR"/seg_*.wav 是按字典序排的，
#   不补零的话 seg_10.wav 会排到 seg_2.wav 前面，绝对时间就全乱了。
T0_MS=$((SEG_SEC * 1000 + LEAD_MS))
T0="$((T0_MS / 1000)).$(printf '%03d' $((T0_MS % 1000)))"
K=0
while :; do
    F=$(printf '%s/seg_%04d.wav' "$SEGDIR" $K)
    if [ $K -eq 0 ]; then
        "$BIN_FFMPEG" -y -hide_banner -loglevel error \
            -t "$T0" -i "$WAV" -af "adelay=${LEAD_MS}:all=1" \
            -ar 16000 -ac 1 -c:a pcm_s16le "$F" || {
            echo "[错误] 切段失败（窗口 $K）"
            cleanup
            exit 1
        }
    else
        SS_MS=$((K * SEG_SEC * 1000 - LEAD_MS))
        SS="$((SS_MS / 1000)).$(printf '%03d' $((SS_MS % 1000)))"
        "$BIN_FFMPEG" -y -hide_banner -loglevel error \
            -ss "$SS" -t $LEAD_WIN -i "$WAV" \
            -ar 16000 -ac 1 -c:a pcm_s16le "$F" || {
            echo "[错误] 切段失败（窗口 $K，-ss $SS）"
            cleanup
            exit 1
        }
    fi
    SZ=$(wc -c < "$F" 2>/dev/null | tr -d ' ')
    case "$SZ" in ''|*[!0-9]*) SZ=0 ;; esac
    if [ "$SZ" -lt 3200 ]; then
        # 只剩前置区、没有本窗口该管的音频：空窗口，删掉收工
        rm -f "$F"
        break
    fi
    K=$((K + 1))
    if [ "$SZ" -lt "$FULL_WIN" ]; then
        # 已切到文件末尾：本窗口就是最后一段，保留后收工
        break
    fi
done
NSEG=$K
if [ "$NSEG" -eq 0 ]; then
    echo "[错误] 没切出任何分段"
    cleanup
    exit 1
fi
echo "      -> 切成 $NSEG 段（每段 ${SEG_SEC}s，前后各留 ${LEAD_SEC}s 余量）"

# ── 识别车道统一函数：把一批 wav 用指定车道识别，JSON 追加到 $1 ──
#   $1 = 输出文件；$2 = 车道（zipformer|sensevoice）；$3.. = wav（必须在调用前写好批次标记）
#   一次把 276 个 wav 全塞进去会被 OOM killer 杀掉（实测日志出现 "Killed"），
#   所以按 24 段一批，模型每批重载一次（约 1.7s），换来内存平稳。
recognize() {
    local dest="$1" kind="$2"; shift 2
    local chunk="" k=0 IDX=0
    : > "$dest"
    while [ $# -gt 0 ]; do
        chunk="$chunk $1"
        shift
        k=$((k + 1))
        if [ $k -ge 24 ] || [ $# -eq 0 ]; then
            # 段号按"文件个数"累计（不是按 JSON 行数）的批次标记：某个 wav 没语音就
            # 不出 JSON 行，asmr_mobile 得靠这个标记知道"下一条 JSON 属于文件 IDX"，
            # 否则空窗之后的句子段号会错位一格。
            echo "#SEG_OFFSET $IDX" >> "$dest"
            if [ "$kind" = "zipformer" ]; then
                LD_LIBRARY_PATH="$D" $NICE $TASKSET "$BIN_SHERPA" \
                    --model-type=transducer \
                    --tokens="$ZIP_DIR/tokens.txt" \
                    --encoder="$ZIP_DIR/encoder.int8.onnx" \
                    --decoder="$ZIP_DIR/decoder.onnx" \
                    --joiner="$ZIP_DIR/joiner.onnx" \
                    --num-threads=$THREADS \
                    $chunk >> "$dest" 2>> "$W/asr.err"
            else
                LD_LIBRARY_PATH="$D" $NICE $TASKSET "$BIN_SHERPA" \
                    --sense-voice-model="$SV_DIR/model.int8.onnx" \
                    --tokens="$SV_TOKENS" \
                    --sense-voice-language=ja \
                    --num-threads=$THREADS \
                    $chunk >> "$dest" 2>> "$W/asr.err"
            fi
            IDX=$((IDX + k))
            chunk=""
            k=0
        fi
    done
}

# ── 融合：zipformer 全片主车道 + 哑窗/空窗 SenseVoice 补车道 ──
if [ "$ASR_KIND" = "fused" ]; then
    : > "$W/asr.err"
    echo "[3/4b] 主车道识别（zipformer 全片）..."
    recognize "$ZIP_TXT" zipformer "$SEGDIR"/seg_*.wav
    echo "      -> 主车道 $(grep -c '"text"' "$ZIP_TXT" 2>/dev/null) 段"
    ZSEGS=$(grep -c '"text"' "$ZIP_TXT" 2>/dev/null)
    case "$ZSEGS" in ''|*[!0-9]*) ZSEGS=0 ;; esac
    if [ "$ZSEGS" -eq 0 ]; then
        echo "[错误] 主车道没识别出任何语音"
        if [ -s "$W/asr.err" ]; then echo "     识别器输出："; tail -5 "$W/asr.err"; fi
        cleanup
        exit 1
    fi

    # scan：asmr_mobile 扫出哑窗/空窗窗口号（每行一个），供补车道精准补漏
    "$BIN_MT" "$ZIP_TXT" /dev/null --port $PORT --seg-sec $SEG_SEC --lead $LEAD_SEC \
        --scan "$DUMB_LIST" --total-win $NSEG 2>&1 | tail -2

    if [ -s "$DUMB_LIST" ]; then
        echo "      -> 补车道对 $(wc -l < "$DUMB_LIST" | tr -d ' ') 个哑窗/空窗补跑 SenseVoice..."
        : > "$SV_TXT"
        while read -r WN; do
            case "$WN" in ''|*[!0-9]*) continue ;; esac
            F=$(printf '%s/seg_%04d.wav' "$SEGDIR" $WN)
            [ -f "$F" ] || continue
            # 单窗单标记：段号 = 哑窗号，绝对时间才正确
            echo "#SEG_OFFSET $WN" >> "$SV_TXT"
            LD_LIBRARY_PATH="$D" $NICE $TASKSET "$BIN_SHERPA" \
                --sense-voice-model="$SV_DIR/model.int8.onnx" \
                --tokens="$SV_TOKENS" \
                --sense-voice-language=ja \
                --num-threads=$THREADS \
                "$F" >> "$SV_TXT" 2>> "$W/asr.err"
        done < "$DUMB_LIST"
        echo "      -> 补车道 $(grep -c '"text"' "$SV_TXT" 2>/dev/null) 段"
        echo "[4/4] 融合翻译并写字幕（zipformer 保留 + SenseVoice 补漏）..."
        "$BIN_MT" "$ZIP_TXT" "$OUT" --port $PORT --seg-sec $SEG_SEC --lead $LEAD_SEC \
            --backfill "$SV_TXT" --total-win $NSEG 2>&1 | tail -3
    else
        # 无哑窗/空窗：zipformer 结果即最终（省 SenseVoice 全片）
        echo "      -> 无哑窗/空窗，直接采用 zipformer 主车道"
        echo "[4/4] 翻译并写字幕..."
        "$BIN_MT" "$ZIP_TXT" "$OUT" --port $PORT --seg-sec $SEG_SEC --lead $LEAD_SEC 2>&1 | tail -3
    fi
else
    # ── 单车道（zipformer 或 SenseVoice）──
    : > "$W/asr.err"
    recognize "$TXT" "$ASR_KIND" "$SEGDIR"/seg_*.wav
    SEGS=$(grep -c '"text"' "$TXT" 2>/dev/null)
    case "$SEGS" in ''|*[!0-9]*) SEGS=0 ;; esac
    echo "      -> 识别到 $SEGS 段"
    if [ "$SEGS" -eq 0 ]; then
        echo "[错误] 没识别出任何语音"
        if [ -s "$W/asr.err" ]; then echo "     识别器输出："; tail -5 "$W/asr.err"; fi
        cleanup
        exit 1
    fi
    echo "[4/4] 翻译并写字幕..."
    "$BIN_MT" "$TXT" "$OUT" --port $PORT --seg-sec $SEG_SEC --lead $LEAD_SEC 2>&1 | tail -3
fi

rm -rf "$SEGDIR" "$ZIP_TXT" "$SV_TXT" "$DUMB_LIST"

# ★ 这里必须走 cleanup 的判断：.wav 输入时 WAV="$IN"，就是用户交进来的原始文件。
#   旧代码这里直接 rm -f "$WAV"，等于**处理完就把用户的源文件删了**（实测踩过：
#   asmr01.wav 跑完字幕后从 input 目录消失）。cleanup() 里一直有这个防护，
#   只是成功路径没调用它。
if [ -n "$WAV" ] && [ "$WAV" != "$IN" ]; then rm -f "$WAV"; fi
rm -f "$TXT"
t_end=$(date +%s)
echo "==== 完成：$OUT + ${OUT%.srt}.lrc（总耗时 $((t_end - t_start)) 秒）===="
