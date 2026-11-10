//! 安卓端字幕流水线的小工具：把 ASR 输出翻译成中文并写成 SRT。
//!
//! 用法（在手机上）：
//!     asmr_mobile <asr输出.txt> <out.srt> [--port 18081] [--no-translate] [--seg-sec 20]
//!
//! 支持两种输入格式，自动识别：
//!
//! 1) sherpa-onnx-offline 的多文件输出（推荐，长音频用这个）：
//!        seg_0000.wav
//!        ----
//!        {"text": "…", "timestamps": [...], "tokens": [...]}
//!        seg_0001.wav
//!        ----
//!        {...}
//!    每段的时间戳是段内相对时间，用文件名里的序号 × seg_sec 换成绝对时间；
//!    再按 token 之间的时间空隙断句（否则 20 秒一段会变成一条巨型字幕）。
//!
//! 2) sherpa-onnx-vad-with-offline-asr 的输出（每段一行）：
//!        172.382 -- 174.156: この前一緒に見ました
//!
//! 翻译走 127.0.0.1 上已经起好的 llama-server（Hy-MT2-1.8B），
//! 只用纯 std 的 TcpStream 手写一个最小 HTTP/1.1 POST，不引 reqwest / ureq
//! —— 手机上没有 TLS 需求，也不想为这点功能塞进一堆依赖。

use serde_json::{json, Value};
use std::collections::BTreeSet;
use std::env;
use std::fs;
use std::io::{Read, Write};
use std::net::TcpStream;
use std::process::exit;
use std::time::{Duration, Instant};

/// 与 PC 端 `light_translate.py` 的 SYSTEM 完全一致。
const SYSTEM: &str = "你是日译中字幕翻译。把用户给的日语翻译成简体中文，口语化、句子短促，\
只输出中文，不要保留日文，不要解释，译文必须是一行、不要换行。\n\
示例：\n\
日语：今日はいい天気ですね\n中文：今天天气真不错呢\n\
日语：ちょっと待ってください\n中文：请稍等一下\n\
日语：そろそろ帰りましょうか\n中文：差不多该回去了吧";

/// 句末停顿超过这个秒数就断句。
const GAP_SPLIT: f64 = 0.55;
/// 最后一句的尾巴时长（token 时间戳只标起点，没有终点）。
const TAIL: f64 = 0.45;

struct Seg {
    start: f64,
    end: f64,
    text: String,
}

/// 一个识别车道的完整解析结果。win_of 与 segs 一一对应，记每条字幕属于哪个窗口，
/// 融合时靠它判定"这个窗口 zipformer 是不是哑窗"。
struct Lane {
    segs: Vec<Seg>,
    win_of: Vec<u64>,
}

/// 哑窗阈值：窗口里 zipformer 识别出的最长一句 ≤ 这个长度的字，就认定该窗口是
/// 哑窗（只吐了"あっ/うん/はあ"之类冷启动感叹词，后半段漏了）。实测 3_舔耳朵 素材
/// 里正常窗最低 7 字（k24），所有哑窗都是 2 字；取 4 有安全裕量。
const DUMB_WIN_MAX: usize = 4;

fn is_punct(ch: char) -> bool {
    matches!(ch, '。' | '！' | '？' | '!' | '?' | '、' | '…' | '，' | ',')
}

/// 解析 `start -- end: text`；不以数字开头的行视为上一句的续行。
fn parse_vad(path: &str, raw: &str) -> Vec<Seg> {
    let mut out: Vec<Seg> = Vec::new();
    for line in raw.lines() {
        let t = line.trim();
        if t.is_empty() {
            continue;
        }
        // 形如 "12.345 -- 67.890: 文本"
        let dash = match t.find("--") {
            Some(p) => p,
            None => {
                if let Some(last) = out.last_mut() {
                    if !last.text.is_empty() {
                        last.text.push(' ');
                    }
                    last.text.push_str(t);
                }
                continue;
            }
        };
        let start = t[..dash].trim().parse::<f64>().ok();
        let rest = t[dash + 2..].trim();
        let (end, text) = match rest.find(':') {
            Some(p) => {
                let e = rest[..p].trim().parse::<f64>().ok();
                (e, rest[p + 1..].trim().to_string())
            }
            None => continue,
        };
        match (start, end) {
            (Some(s), Some(e)) if e > s => out.push(Seg { start: s, end: e, text }),
            _ => continue,
        }
    }
    let _ = path;
    out
}

/// 从 `seg_0007.wav` 里取出 7。取不到返回 None。
fn seg_index(line: &str) -> Option<u64> {
    let name = line.rsplit('/').next().unwrap_or(line);
    let digits: String = name.chars().filter(|c| c.is_ascii_digit()).collect();
    if digits.is_empty() {
        return None;
    }
    digits.parse::<u64>().ok()
}

/// 解析 sherpa-onnx-offline 的多文件输出，顺便按时间空隙断句。
///
/// 注意：sherpa 把文件名标题打到 stderr、JSON 打到 stdout，所以文件名到不了这里。
/// 改用 run.sh 在每批前写入的 `#SEG_OFFSET <已处理段数>` 标记来换算绝对时间；
/// 没有标记时（比如单独手动调用）退回「按 JSON 出现顺序 × seg_sec」。
///
/// run.sh 现在是**逐窗口交叉切段**：窗口 k = 真实音频 [k*seg_sec - lead, k*seg_sec + seg_sec + lead]，
/// 两侧各留 lead 秒余量，只认段内 lead <= ts < lead + seg_sec 的 token，
/// 即真实 [k*seg_sec, (k+1)*seg_sec)——相邻窗口的归属区间首尾相接且互不重叠。
/// 所以：真实 = offset + local，offset = k*seg_sec - lead，floor / ceil 都只跟 lead 有关。
/// 上界必须有：窗口 k 的音频比它该管的区间还多出 lead 秒，不砍掉就会和窗口 k+1 重复输出
/// （实测：不加时会得到「…私とお姉ちゃんは」+「姉ちゃんはちゃんと家の仕事を…」这种重复）。
fn parse_sherpa(path: &str, raw: &str, seg_sec: f64, lead: f64) -> Lane {
    let _ = path;
    let mut out: Vec<Seg> = Vec::new();
    // 每条段属于哪个窗口（与 out 一一对应）。窗口接缝处的叠字只能靠它定位：
    // 用「两条时间挨得近」之类的时间阈值猜接缝并不可靠。
    let mut win_of: Vec<u64> = Vec::new();
    // 这两项在每个 JSON 行开头按段号算好再读，所以不给初值（给了也是死赋值）。
    let mut offset: f64;
    // 本段里 floor <= ts < ceil 的 token 才归本段：floor 之前是留给模型补上下文的
    // 余量（第 0 段那段是 run.sh 用 adelay 垫出来的真静音），
    // ceil 之后是留给下一窗口的余量，本段不该重复报（否则两个窗口会各报一遍同一句话）。
    let mut floor: f64;
    let mut ceil: f64;
    let mut json_seen = 0u64;

    for line in raw.lines() {
        let t = line.trim();
        if t.is_empty() || t == "----" {
            continue;
        }

        // run.sh 写的批次标记
        if let Some(rest) = t.strip_prefix("#SEG_OFFSET") {
            if let Ok(n) = rest.trim().parse::<u64>() {
                // offset 在下面每个 JSON 行统一按 json_seen 重算，这里只记段号
                json_seen = n;
            }
            continue;
        }

        // 万一有文件名标题行（某些调用方式会写进 stdout），也能用
        if t.ends_with(".wav") || t.ends_with(".WAV") {
            if let Some(n) = seg_index(t) {
                json_seen = n;
            }
            continue;
        }

        if !t.starts_with('{') {
            continue;
        }

        // 按出现顺序推进。run.sh 逐窗口切段，窗口 i 的段内 0 秒对应真实音频的
        // i*seg_sec - lead 秒，所以整个偏移量恒为 -lead。
        let idx = json_seen;
        offset = idx as f64 * seg_sec - lead;
        // 段内 ts < lead 的 token 属于前置区，一律丢掉：那是留给模型的上下文。
        // 从第 1 段起它是上一段窗口的真实音频（会由上一段归口），第 0 段则
        // 是 run.sh 用 adelay 垫出来的真静音——顺手把「开头凭空吐字」的幻觉也压掉了。
        // 同理段内 ts >= lead + seg_sec 的 token 属于本窗口多出来的尾，归下一段管。
        floor = lead;
        ceil = lead + seg_sec;
        json_seen += 1;

        let v: Value = match serde_json::from_str(t) {
            Ok(v) => v,
            Err(_) => continue,
        };

        let text = v["text"].as_str().unwrap_or("").trim().to_string();
        if text.is_empty() {
            continue;
        }

        let ts: Vec<f64> = v["timestamps"]
            .as_array()
            .map(|a| a.iter().filter_map(|x| x.as_f64()).collect())
            .unwrap_or_default();
        let tokens: Vec<String> = v["tokens"]
            .as_array()
            .map(|a| {
                a.iter()
                    .map(|x| x.as_str().unwrap_or("").to_string())
                    .collect()
            })
            .unwrap_or_default();

        // token 与时间戳对不上就整段当一条，至少不丢内容
        if tokens.len() < 2 || tokens.len() != ts.len() {
            let last = ts.last().copied().unwrap_or(seg_sec);
            let first = ts.first().copied().unwrap_or(0.0);
            // 整条落在本窗口该管的区间之外（比如全在前置区里，即第 0 段的静音垫）
            // ——没有本段该报的内容，别造一条空字幕
            if last < floor || first >= ceil {
                continue;
            }
            let end = last + TAIL;
            win_of.push(idx);
            out.push(Seg {
                start: offset + floor,
                end: (offset + end).max(offset + floor + 0.5),
                text,
            });
            continue;
        }

        let mut cur = String::new();
        let mut cur_start = 0.0f64;
        let mut prev_ts = 0.0f64;
        // 是否已经有保留下来的 token（前置区的 token 会被跳过，所以不能拿 i 判断）
        let mut has_prev = false;
        let mut prev_punct = false;

        for i in 0..tokens.len() {
            if ts[i] < floor || ts[i] >= ceil {
                continue;
            }
            let gap = if has_prev { ts[i] - prev_ts } else { 0.0 };
            // 切点两侧都是数字字就别切（见 is_numeral 的注释）
            let num_cut = has_prev
                && cur.chars().last().map(is_numeral).unwrap_or(false)
                && tokens[i].chars().next().map(is_numeral).unwrap_or(false);

            if has_prev && (gap > GAP_SPLIT || prev_punct) && !num_cut {
                let piece = cur.trim().to_string();
                if !piece.is_empty() {
                    win_of.push(idx);
                    out.push(Seg {
                        start: offset + cur_start,
                        end: offset + ts[i],
                        text: piece,
                    });
                }
                cur.clear();
            }

            // 一段话的第一个字（含刚被切开之后的第一字）决定这条字幕的起点
            if cur.is_empty() {
                cur_start = ts[i];
            }
            cur.push_str(&tokens[i]);
            prev_ts = ts[i];
            has_prev = true;
            prev_punct = tokens[i].chars().last().map(is_punct).unwrap_or(false);
        }

        let piece = cur.trim().to_string();
        if !piece.is_empty() {
            let end = prev_ts + TAIL;
            win_of.push(idx);
            out.push(Seg {
                start: offset + cur_start,
                end: offset + end,
                text: piece,
            });
        }
    }

    trim_seams(&mut out, &win_of);
    Lane { segs: out, win_of }
}

/// 抹掉窗口接缝处的叠字。
///
/// 每个窗口都会一路报到自己的上界，下一个窗口又从同一个时刻重新开报，于是压在接缝上的
/// 一两个字可能被两边各报一次 —— 实测得到过「…私とお姉」+「姉ちゃんはちゃんと家の仕事を…」
/// 和「…だって毎日ご主」+「ご主人様のこと考えていますよ」这种叠字。
/// （上界必须砍，不然窗口 k 会把窗口 k+1 该管的那 1.5 秒整句再报一遍，重复得更厉害，
///   所以这里是在「砍干净」之后补救接缝，而不是把上界放开。）
///
/// 只处理真正的窗口接缝（相邻两条分别属于不同窗口），并且要求两条在时间上确实挨着，
/// 免得跨过「某个窗口什么都没识别出来」的缺口去误裁。
fn trim_seams(out: &mut Vec<Seg>, win_of: &[u64]) {
    // 最多往回吃几个字。接缝上的重复基本是 1~2 个字，留点余量。
    const MAX: usize = 6;
    // 接缝落在词中间、两半需要并回去时的上限（并起来的时长 / 字数）。
    // 上界比 merge_segs 的 6.5s 宽：接缝处是被硬切开的一句，本来就比正常一句长。
    const SEAM_MERGE_MAX_S: f64 = 9.0;
    const SEAM_MERGE_MAX_CHARS: usize = 40;
    let mut drop: Vec<usize> = Vec::new();
    for j in 1..out.len() {
        if win_of[j] == win_of[j - 1] {
            continue; // 同一个窗口内部，不是接缝
        }
        if out[j].start - out[j - 1].end >= 0.6 {
            continue; // 两条之间隔着一段没识别出来的音频，不是接缝
        }
        let a: Vec<char> = out[j - 1].text.chars().collect();
        let b: Vec<char> = out[j].text.chars().collect();
        let n = MAX.min(a.len()).min(b.len());
        // 取最长的「前一条后缀 == 后一条前缀」重合长度
        let mut k = 0usize;
        for m in 1..=n {
            if a[a.len() - m..] == b[..m] {
                k = m;
            }
        }
        let rest: String = b[k..].iter().collect();
        // 接缝上两条挨着、并起来还不算长，就并成一条。切点是按秒硬切的，落在词中间时
        // 两半各自都不成句：窗口 k 只报出「…私とお姉」，窗口 k+1 接着报「ちゃんはちゃんと家の仕事を…」。
        // 分开交给翻译就会被当成两句 —— 实测这一例把孤立的「ちゃんは」译成了人名「小千」，
        // 而两半拼起来（「…私とお姉ちゃんはちゃんと家の仕事を…」）本来就是完整的一句。
        // 并起来超过 SEAM_MERGE_MAX_S 说明接缝上本来就是两个长句，那就只抹掉重合的字。
        if out[j].end - out[j - 1].start <= SEAM_MERGE_MAX_S
            && a.len() + b.len() - k <= SEAM_MERGE_MAX_CHARS
        {
            out[j - 1].text.push_str(&rest);
            out[j - 1].end = out[j].end;
            drop.push(j);
            continue;
        }
        if k == 0 {
            continue; // 没有重合的字要抹，两条各自留着
        }
        if rest.trim().is_empty() {
            // 整条都只是重复：连这条字幕一起删掉
            drop.push(j);
        } else {
            out[j].text = rest;
        }
    }
    if !drop.is_empty() {
        let mut keep: Vec<Seg> = Vec::with_capacity(out.len());
        for (i, s) in out.drain(..).enumerate() {
            if !drop.contains(&i) {
                keep.push(s);
            }
        }
        *out = keep;
    }
}

/// 扫描一条车道（zipformer 主），找出所有"哑窗"窗口号。
///
/// 哑窗 = 该窗口最多只有一条 ≤ DUMB_WIN_MAX 个字的段（冷启动只吐了"あっ/うん"），
/// 或整个窗口没有段（空窗）。这些窗口的音频里其实可能有内容，只是 zipformer 漏了，
/// 需要外部（run.sh）对这些窗口补跑一次 SenseVoice。
///
/// 返回需补窗口号列表（升序、去重）。
/// 一个窗口里所有段的最长文本长度。窗口索引从 0 开始的连续递增序列，
/// 但 parse_sherpa 只给实际有内容的窗口建了段——窗口之间可能有整窗空白。
fn window_max_chars(lane: &Lane, win: u64) -> usize {
    let mut mx = 0usize;
    for (i, s) in lane.segs.iter().enumerate() {
        if lane.win_of[i] == win {
            mx = mx.max(s.text.chars().count());
        }
    }
    mx
}

/// 找出主车道（zipformer）里所有"哑窗/空窗"窗口号。
///
/// 判定：该窗口内部**所有段合起来的最长一句** ≤ DUMB_WIN_MAX 个字（哑窗，
/// 冷启动只吐了"あっ/うん"之类），或该窗口完全没有段（空窗）。
/// 这些窗口需要外部（run.sh）对它们补跑一次 SenseVoice。
///
/// 按窗口聚合判断，而不是按单条段——一个长句断句后会有多个 2~4 字的碎片，
/// 只看单条会把正常长句窗口误判成哑窗。
/// total_win：总窗口数（含无段窗口），用于补齐尾部空窗。
fn find_dumb_windows(lane: &Lane, total_win: usize) -> Vec<u64> {
    // 收集有段出现的窗口集合
    let mut seen: BTreeSet<u64> = BTreeSet::new();
    for &w in &lane.win_of {
        seen.insert(w);
    }
    let mut need: Vec<u64> = Vec::new();
    for w in 0..total_win as u64 {
        let is_empty = !seen.contains(&w);
        let is_dumb = !is_empty && window_max_chars(lane, w) <= DUMB_WIN_MAX;
        if is_empty || is_dumb {
            need.push(w);
        }
    }
    need
}

/// 把补车道（SenseVoice）的段并进主车道（zipformer）。
///
/// 策略（zipformer 主、SV 补漏）：
///   主车道正常的窗口全部保留；主车道哑窗窗口的段被丢弃，改用补车道同一窗口的段填充。
///   补车道只被要求对"哑窗"识别，所以它的段天然都落在主车道需要补的地方。
///   用窗口号对齐两车道的段（不是时间戳），因为两套时间戳是同源硬切的 seg_*.wav。
///
/// keep_sv_for_mask：为调试/校验传入一个"被替换掉的哑窗号集合"（返回它）。
/// 这里是纯融合：返回 (融合段, 被替换的哑窗号)。
fn fuse_lanes(main: Lane, fill: Lane, total_win: usize) -> (Vec<Seg>, Vec<u64>) {
    // 1. 主车道所有段默认保留，但哑窗/空窗窗口的段要被替换
    let replaced = find_dumb_windows(&main, total_win);
    let replaced_set: BTreeSet<u64> = replaced.iter().copied().collect();

    // 2. 主车道：剔掉哑窗/空窗窗口的段（那些窗口交给补车道）
    let mut out: Vec<Seg> = Vec::new();
    for (i, s) in main.segs.into_iter().enumerate() {
        let w = main.win_of[i];
        if !replaced_set.contains(&w) {
            out.push(s);
        }
    }

    // 3. 补车道：只把"被替换窗口"的段填进来（同一窗口号对齐）
    for (i, s) in fill.segs.into_iter().enumerate() {
        let w = fill.win_of[i];
        if replaced_set.contains(&w) {
            out.push(s);
        }
    }

    // 4. 融合后时间排序 + 碎片合并（merge_segs 会在翻译前减少碎片）
    out.sort_by(|a, b| a.start.partial_cmp(&b.start).unwrap_or(std::cmp::Ordering::Equal));
    let mut segs = merge_segs(out);
    let mut last_end = 0.0f64;
    for s in segs.iter_mut() {
        if s.start < last_end {
            s.start = last_end;
        }
        if s.end <= s.start {
            s.end = s.start + 1.0;
        }
        last_end = s.end;
    }
    (segs, replaced)
}

/// 强句末标点：以这些结尾基本可确定一句话说完了，不该再往外并。
/// 与 is_punct 不同 —— 那个含逗号顿号，是用来「断句」的，这里要的是「结句」。
fn is_hard_punct(ch: char) -> bool {
    matches!(ch, '。' | '！' | '？' | '!' | '?')
}

/// 数字串里的字（含日期单位）。
///
/// 断句切在这种字中间会把一个数劈成两条 —— 实测官方素材第一句
/// 「このお話は千九百七十二年六月十三日に…」，模型在「百|七」处报了 0.56s 的间隔
/// （> GAP_SPLIT=0.55），于是切成「千九百七」+「十二年六月十三日…」，
/// 翻出来是「话题是1900年」+「七十二年六月十三日」两条都错的字幕。
fn is_numeral(ch: char) -> bool {
    matches!(
        ch,
        '〇' | '零'
            | '一'
            | '二'
            | '三'
            | '四'
            | '五'
            | '六'
            | '七'
            | '八'
            | '九'
            | '十'
            | '百'
            | '千'
            | '万'
            | '億'
            | '兆'
            | '年'
            | '月'
            | '日'
    )
}

/// 把断句断碎的相邻条重新并回去。
///
/// 为什么要这一步：SenseVoice 的 token 时间戳在 ASMR 素材上抖动很大，
/// GAP_SPLIT=0.55 会把「感冒？还是说翘课了？」切成「风」+「还是偷懒呢」这种碎片。
/// 实测同一段素材安卓出 48 条（字数中位 4）、PC 全量模式只 14 条（字数中位 13）。
/// 碎片既难读，又让翻译多跑几倍请求（短句没上下文，译文更容易怪）。
/// 合并放在翻译**之前**：一次改动同时改善质量和速度。
///
/// 合并条件（全部满足才并，宁可保守）：
///   · 间隔 <= GAP_MERGE_S（贴着说下来的）
///   · 并完总时长 <= MERGE_MAX_DUR（不造巨型字幕）
///   · 并完字数 <= MERGE_MAX_CHARS
///   · 前一条不是强句末标点结尾；或前一条短到明显是被切下来的半句
fn merge_segs(segs: Vec<Seg>) -> Vec<Seg> {
    const GAP_MERGE_S: f64 = 0.30;
    const MERGE_MAX_DUR: f64 = 6.5;
    const MERGE_MAX_CHARS: usize = 40;
    const SHORT_CHARS: usize = 6;
    const MIN_SHOW_S: f64 = 1.0;

    let mut out: Vec<Seg> = Vec::with_capacity(segs.len());
    for s in segs {
        let mut merged = false;
        if let Some(prev) = out.last_mut() {
            let gap = s.start - prev.end;
            let new_dur = s.end - prev.start;
            let prev_chars = prev.text.chars().count();
            let new_chars = prev_chars + s.text.chars().count();
            let prev_hard = prev
                .text
                .chars()
                .last()
                .map(is_hard_punct)
                .unwrap_or(false);
            let ok = gap <= GAP_MERGE_S
                && new_dur <= MERGE_MAX_DUR
                && new_chars <= MERGE_MAX_CHARS
                && (!prev_hard || prev_chars <= SHORT_CHARS);
            if ok {
                prev.text.push_str(&s.text);
                prev.end = s.end;
                merged = true;
            }
        }
        if !merged {
            out.push(s);
        }
    }

    // 最短显示时长：太短的条来不及读，只在不撞下一条的前提下往后撑。
    for i in 0..out.len() {
        if out[i].end - out[i].start >= MIN_SHOW_S {
            continue;
        }
        let want = out[i].start + MIN_SHOW_S;
        out[i].end = if i + 1 < out.len() {
            want.min(out[i + 1].start)
        } else {
            want
        };
    }
    out
}

#[cfg(test)]
mod tests {
    use super::*;

    fn seg(start: f64, end: f64, text: &str) -> Seg {
        Seg { start, end, text: text.to_string() }
    }
    fn lane(segs: Vec<Seg>, wins: Vec<u64>) -> Lane {
        Lane { segs, win_of: wins }
    }

    #[test]
    fn dumb_windows_picks_empty_and_short() {
        // 模拟 zipformer：窗0 有长句，窗1 空(无段)，窗2 只有感叹词，窗3 正常长句
        // 窗1 在 Lane 里完全没有段（空窗）
        let l = lane(
            vec![
                seg(0.0, 10.0, "ご主人様がいないと寂しい"),
                seg(41.0, 45.0, "うん"),             // 窗2 哑
                seg(43.6, 44.0, "あっ"),             // 窗2 哑(碎片)
                seg(60.0, 70.0, "ちゃんと家の仕事をします"),
            ],
            vec![0, 2, 2, 3],
        );
        // total_win=4 → 窗1 空、窗2 哑
        let need = find_dumb_windows(&l, 4);
        assert_eq!(need, vec![1, 2]);
    }

    #[test]
    fn fused_keeps_main_and_fills_blank() {
        // main: 窗0 长句(保留)、窗1 空、窗2 哑(仅感叹词)、窗3 长句
        let main = lane(
            vec![
                seg(0.0, 10.0, "ご主人様がいないと寂しい"),
                seg(41.0, 45.0, "うん"),
                seg(60.0, 70.0, "ちゃんと家の仕事をします"),
            ],
            vec![0, 2, 3],
        );
        // fill: 只对哑窗/空窗识别 → 窗1 完整句、窗2 完整句
        let fill = lane(
            vec![
                seg(20.0, 30.0, "私とお姉ちゃんはちゃんと"),
                seg(41.0, 48.0, "お姉ちゃんもいっしょに待ってます"),
            ],
            vec![1, 2],
        );
        let (out, replaced) = fuse_lanes(main, fill, 4);
        // 替换窗 = [1,2]
        assert_eq!(replaced, vec![1, 2]);
        let all = out.iter().map(|s| s.text.as_str()).collect::<Vec<_>>();
        // 窗0、窗3 的主车道段 + 窗1、窗2 的补车道段
        assert!(all.contains(&"ご主人様がいないと寂しい"));
        assert!(all.contains(&"ちゃんと家の仕事をします"));
        assert!(all.contains(&"私とお姉ちゃんはちゃんと"));
        assert!(all.contains(&"お姉ちゃんもいっしょに待ってます"));
        // 被替换的哑窗感叹词"うん"应消失
        assert!(!all.contains(&"うん"));
    }

    #[test]
    fn fused_no_backfill_keeps_single_lane() {
        let l = lane(vec![seg(0.0, 5.0, "あいさつです")], vec![0]);
        // 无补车道时直接拿 segs 就是主车道
        let (out, replaced) = fuse_lanes(l, lane(vec![], vec![]), 1);
        assert_eq!(replaced, Vec::<u64>::new());
        assert_eq!(out.len(), 1);
        assert_eq!(out[0].text, "あいさつです");
    }
}

fn fmt_ts(sec: f64) -> String {
    let total_ms = (sec.max(0.0) * 1000.0).round() as u64;
    let h = total_ms / 3_600_000;
    let m = (total_ms / 60_000) % 60;
    let s = (total_ms / 1000) % 60;
    let ms = total_ms % 1000;
    format!("{h:02}:{m:02}:{s:02},{ms:03}")
}

/// 对 127.0.0.1:port 发一次 /v1/chat/completions，返回 content。
fn translate(port: u16, text: &str, temp: f64) -> Result<String, String> {
    let body = json!({
        "messages": [
            {"role": "system", "content": SYSTEM},
            {"role": "user", "content": text}
        ],
        "temperature": temp,
        "max_tokens": 128,
        "stream": false
    })
    .to_string();

    let mut sock = TcpStream::connect(("127.0.0.1", port))
        .map_err(|e| format!("连不上 llama-server(127.0.0.1:{port}): {e}"))?;
    sock.set_read_timeout(Some(Duration::from_secs(180))).ok();
    sock.set_nodelay(true).ok();

    let req = format!(
        "POST /v1/chat/completions HTTP/1.1\r\n\
         Host: 127.0.0.1:{port}\r\n\
         Content-Type: application/json\r\n\
         Content-Length: {}\r\n\
         Connection: close\r\n\r\n{}",
        body.as_bytes().len(),
        body
    );
    sock.write_all(req.as_bytes())
        .map_err(|e| format!("发送失败: {e}"))?;
    sock.flush().ok();

    let mut raw = Vec::new();
    sock.read_to_end(&mut raw)
        .map_err(|e| format!("读取失败: {e}"))?;

    let pos = raw
        .windows(4)
        .position(|w| w == b"\r\n\r\n")
        .ok_or_else(|| "响应里没有头部结束标记".to_string())?;
    let payload = &raw[pos + 4..];
    let v: Value = serde_json::from_slice(payload).map_err(|e| {
        format!(
            "JSON 解析失败: {e}（原始前 120 字节: {:?}）",
            String::from_utf8_lossy(&payload[..payload.len().min(120)])
        )
    })?;
    let content = v["choices"][0]["message"]["content"]
        .as_str()
        .ok_or_else(|| "响应里没有 choices[0].message.content".to_string())?;
    // SRT 一条字幕里不能有换行，多行会被播放器当成两条
    Ok(content.trim().replace('\n', " ").replace('\r', ""))
}

/// 一句翻译的重试封装。照 PC 版 v1.28.5 的教训：
/// 只请求一次的话，超时或空串会一路抛到上层，用户看到的就是「有些话根本没翻译」。
/// 后两次把 temperature 抬到 0.3 —— 第一次失败常常是模型卡在重复/空输出，
/// 升温能跳出来。
fn translate_retry(port: u16, text: &str) -> Result<String, String> {
    let mut last = String::from("未知错误");
    for attempt in 0..3u64 {
        let temp = if attempt == 0 { 0.1 } else { 0.3 };
        match translate(port, text, temp) {
            Ok(t) if !t.trim().is_empty() => return Ok(t),
            Ok(_) => last = "模型返回空".to_string(),
            Err(e) => last = e,
        }
        if attempt < 2 {
            std::thread::sleep(Duration::from_millis(400 * (attempt + 1)));
        }
    }
    Err(last)
}

fn main() {
    let args: Vec<String> = env::args().skip(1).collect();
    if args.len() < 2 {
        eprintln!(
            "用法: asmr_mobile <asr输出.txt> <out.srt> [--port 18081] [--no-translate] [--seg-sec 20] [--lead 1.5] [--backfill SV输出.txt] [--total-win N]"
        );
        exit(2);
    }
    let input = args[0].clone();
    let output = args[1].clone();
    let mut port: u16 = 18081;
    let mut do_translate = true;
    // 可选补车道（SenseVoice）输出文件：给了就走"zipformer 主 + SV 补漏"的融合，
    // 不给就退回原来的单车道。
    let mut backfill: Option<String> = None;
    // 总窗口数：run.sh 切出的窗口数（含空窗）。融合时用它在 time 轴上把结尾空窗补齐。
    // 不传时从主车道解析结果里推最大窗口 + 1。
    let mut total_win: usize = 0;
    let mut seg_sec: f64 = 20.0;
    // run.sh 逐窗口切段时每段归属区两侧各留了 lead 秒余量，绝对时间要整体左移这么多，
    // 段内 ts < lead 的前置区也要丢掉。默认 0 是为了「手动单独调用本程序」时行为不变；
    // run.sh 会显式传 --lead。
    let mut lead: f64 = 0.0;
    // A/B 用：--no-merge 关掉碎片合并，同一个二进制内对比，避免两次编译引入偏差
    let do_merge = !std::env::var("ASMR_NO_MERGE").is_ok_and(|v| v == "1");
    // --scan <file>：只扫描主车道，把"哑窗/空窗"窗口号（每行一个）写到 file 后退出。
    // 供 run.sh 读列表去对这些窗口单独补跑 SenseVoice。不翻译、不写 SRT。
    let mut scan_out: Option<String> = None;
    let mut i = 2;
    while i < args.len() {
        match args[i].as_str() {
            "--port" if i + 1 < args.len() => {
                port = args[i + 1].parse().unwrap_or(18081);
                i += 2;
            }
            "--seg-sec" if i + 1 < args.len() => {
                seg_sec = args[i + 1].parse().unwrap_or(20.0);
                i += 2;
            }
            "--lead" if i + 1 < args.len() => {
                lead = args[i + 1].parse().unwrap_or(0.0);
                i += 2;
            }
            "--no-translate" => {
                do_translate = false;
                i += 1;
            }
            "--backfill" if i + 1 < args.len() => {
                backfill = Some(args[i + 1].clone());
                i += 2;
            }
            "--total-win" if i + 1 < args.len() => {
                total_win = args[i + 1].parse().unwrap_or(0);
                i += 2;
            }
            "--scan" if i + 1 < args.len() => {
                scan_out = Some(args[i + 1].clone());
                i += 2;
            }
            other => {
                eprintln!("未知参数: {other}");
                exit(2);
            }
        }
    }
    if seg_sec <= 0.0 {
        seg_sec = 20.0;
    }
    if !lead.is_finite() || lead < 0.0 {
        lead = 0.0;
    }

    let raw = match fs::read_to_string(&input) {
        Ok(r) => r,
        Err(e) => {
            eprintln!("[错误] 读不到 {input}: {e}");
            exit(1);
        }
    };

    // 有 JSON 行就走多文件格式，否则退回 vad-asr 的 `a -- b: text`
    let is_json = raw
        .lines()
        .any(|l| l.trim_start().starts_with("{\"text\"") || l.trim_start().starts_with("{"));

    // 融合模式：main=(zipformer 主)、backfill=(SenseVoice 补)。否则单车道。
    // asmr_mobile 只负责"解析+融合+翻译+写字幕"，识别本身在 run.sh 完成：
    // run.sh 先跑全片 zipformer 得 main，再用 --scan 定位哑窗，只对哑窗单独跑
    // SenseVoice 得 backfill。两个文件的 JSON 格式同构（text/timestamps/tokens）。
    let mut segs: Vec<Seg>;
    if is_json {
        let main_lane = parse_sherpa(&input, &raw, seg_sec, lead);
        // --scan：只输出哑窗/空窗列表，不翻译不写 SRT。
        if let Some(scan_path) = &scan_out {
            let tw = if total_win > 0 {
                total_win
            } else {
                main_lane.win_of.iter().max().map(|m| (*m + 1) as usize).unwrap_or(0)
            };
            let need = find_dumb_windows(&main_lane, tw);
            let body = need.iter().map(|n| n.to_string()).collect::<Vec<_>>().join("
");
            if let Err(e) = fs::write(scan_path, body + "
") {
                eprintln!("[错误] 写 {scan_path} 失败: {e}");
                exit(1);
            }
            eprintln!(
                "扫描完成：{} 个哑窗/空窗（总窗 {}，主 {} 段）-> {}",
                need.len(),
                tw,
                main_lane.segs.len(),
                scan_path
            );
            exit(0);
        }
        if let Some(sv_path) = &backfill {
            let sv_raw = match fs::read_to_string(sv_path) {
                Ok(r) => r,
                Err(e) => {
                    eprintln!("[错误] 读不到补车道 {sv_path}: {e}");
                    exit(1);
                }
            };
            let sv_lane = parse_sherpa(sv_path, &sv_raw, seg_sec, lead);
            // 总窗口数：优先 --total-win，其次从主车道最大窗口推（+1 含尾部空窗）。
            let tw = if total_win > 0 {
                total_win
            } else {
                main_lane.win_of.iter().max().map(|m| (*m + 1) as usize).unwrap_or(0)
            };
            eprintln!("融合模式：主 {} 段，补 {} 段，总窗 {}，哑窗/空窗替换 …",
                main_lane.segs.len(), sv_lane.segs.len(), tw);
            let (mu, rw) = fuse_lanes(main_lane, sv_lane, tw);
            segs = mu;
            if !rw.is_empty() {
                eprintln!("补漏替换了 {} 个窗口：{:?}", rw.len(), rw);
            }
        } else {
            segs = main_lane.segs;
        }
    } else {
        segs = parse_vad(&input, &raw);
    }

    if segs.is_empty() {
        eprintln!("[错误] {input} 里没有解析到任何分段");
        exit(1);
    }

    // 时间轴兜底：保证单调递增、后一条不早于前一条结束
    segs.sort_by(|a, b| a.start.partial_cmp(&b.start).unwrap_or(std::cmp::Ordering::Equal));
    let mut last_end = 0.0f64;
    for s in segs.iter_mut() {
        if s.start < last_end {
            s.start = last_end;
        }
        if s.end <= s.start {
            s.end = s.start + 1.0;
        }
        last_end = s.end;
    }

    // 断句 → 合并碎片（放在翻译之前，理由见 merge_segs 注释）
    let before = segs.len();
    if do_merge {
        segs = merge_segs(segs);
    }
    if do_merge && before != segs.len() {
        eprintln!(
            "碎片合并：{} 段 -> {} 段（并掉 {} 条，翻译请求随之减少）",
            before,
            segs.len(),
            before - segs.len()
        );
    }

    eprintln!(
        "解析到 {} 段（{}，合并{}），开始{}…",
        segs.len(),
        if is_json { "sherpa 多文件" } else { "vad-asr" },
        if do_merge { "开" } else { "关" },
        if do_translate { "翻译" } else { "写字幕" }
    );

    let t0 = Instant::now();
    let mut srt = String::new();
    let mut failed = 0usize;
    for (n, s) in segs.iter().enumerate() {
        let zh = if do_translate {
            match translate_retry(port, &s.text) {
                Ok(t) => t,
                Err(e) => {
                    failed += 1;
                    eprintln!("  [{}] 翻译失败（已重试 3 次）: {e}", n + 1);
                    // 留原文但加标记：以前静默塞日文，用户根本不知道哪几条没翻译
                    format!("[未译] {}", s.text)
                }
            }
        } else {
            s.text.clone()
        };
        if n < 200 || n % 20 == 0 {
            eprintln!("[{:>7.2}s -> {:>7.2}s] {}", s.start, s.end, zh);
        }
        srt.push_str(&format!(
            "{}\n{} --> {}\n{}\n\n",
            n + 1,
            fmt_ts(s.start),
            fmt_ts(s.end),
            zh
        ));
    }

    if let Err(e) = fs::write(&output, srt) {
        eprintln!("[错误] 写 {output} 失败: {e}");
        exit(1);
    }
    let el = t0.elapsed().as_secs_f64();
    eprintln!(
        "完成：{} 段，失败 {}，耗时 {:.1} 秒（{:.2} 秒/句）-> {}",
        segs.len(),
        failed,
        el,
        el / segs.len() as f64,
        output
    );
    if failed > 0 {
        eprintln!(
            "注意：{} / {} 条翻译失败（已在字幕里标 [未译]，可重跑该文件）",
            failed,
            segs.len()
        );
    }
    if failed > 0 && failed == segs.len() {
        exit(1);
    }
}
