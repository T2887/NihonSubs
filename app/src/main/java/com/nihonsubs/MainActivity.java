package com.nihonsubs;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Environment;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowManager;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;
import java.util.List;
import java.net.HttpURLConnection;
import java.net.URL;

/**
 * ASMR 日语语音 → 中文翻译（手机版）
 *
 * 界面配色照 PC 版（egui）：暖米色底 + 砖红强调色 + 卡片式布局，
 * 但布局按安卓习惯重做：顶部大标题、卡片纵向排布、底部固定动作条、
 * 触摸目标 ≥ 48dp、所有交互都有动效。
 */
public class MainActivity extends Activity {

    // ── 色板（与 PC 版一致）──────────────────────────────
    static final int BG          = 0xFFF0EEE6;
    static final int CARD        = 0xFFFAF9F5;
    static final int CARD_LINE   = 0xFFE3DDD0;
    static final int SUNK        = 0xFFF5F2EA;
    static final int INK         = 0xFF1F1E1D;
    static final int INK2        = 0xFF4A463F;
    static final int INK3        = 0xFF6B6458;
    static final int ACCENT      = 0xFFC96442;
    static final int ACCENT_DEEP = 0xFFB4532F;
    static final int ACCENT_SOFT = 0xFFF7EBE4;
    static final int ACCENT_INK  = 0xFF7D3F26;
    static final int OK_GREEN    = 0xFF5F8A63;
    static final int WARN        = 0xFFA4442C;

    private static final int REQ_PICK = 1001;

    // ── 版本自检（线上最新版本引导下载）────────────────────────
    // 背后的仓库：GitHub 主 + Gitee 兜底，两个地址都放 latest.txt。
    // latest.txt 格式（每行一个，忽略空行与 # 注释）：
    //   VERSION=1.0.0
    //   APK=https://.../app-release.apk
    //   NOTE=可选的更新说明
    private static final String VERSION_URL =
            "https://raw.githubusercontent.com/T2887/NihonSubs/main/latest.txt";
    private static final String VERSION_URL_BACKUP =
            "https://gitee.com/aaa15/NihonSubs/raw/main/latest.txt";
    private TextView updateRowView;   // 「检查更新」按钮（查到新版可变色提示）

    private final Handler ui = new Handler(Looper.getMainLooper());

    private LinearLayout content;
    private LinearLayout fileBox;
    private TextView logView;
    private ScrollView logScroll;
    private TextView stageView, actionView, percentView;
    private View progressFill;
    private FrameLayout progressTrack;
    private TextView startBtn, stopBtn;
    private TextView statView;          // 进度卡的「运行状态」行（CPU/吞吐）
    private TextView previewView;       // 「字幕预览」卡里的最新几句
    private ScrollView previewScroll;
    private TextView subtitleView;
    private TextView modelStateView;   // 环境卡里的「模型」状态文字
    private TextView modelBtn;         // 「导入模型」按钮（导入后置灰不可点）
    private final List<String> picked = new ArrayList<>();
    private TextView pauseBtn;                       // 底部「暂停 / 继续」按钮
    private volatile boolean paused = false;         // 推理进程已被 SIGSTOP 冻结
    private volatile boolean skipDoneFiles = false;  // 续跑上次队列时跳过「完成」的
    private static final String QPREF = "queue";      // 队列持久化（意外退出后续跑）
    // 启动瞬间先抄一份上次的队列：界面上来就会 refreshFiles() 把状态刷成「待处理」，
    // 直接读偏好设置的话，「已完成」的信息在弹窗之前就没了，续跑就全量重跑。
    private String resumeFilesRaw = null;
    private boolean resumeWasRunning = false;

    private Process running;
    private boolean busy = false;
    private float progress = 0f;
    private boolean progressWasDone = false;
    private int notifiedPct = -1;
    private static final int REQ_MODEL = 1002;
    private String pendingModelName = null;
    /** 线上模型包下载地址（latest.txt 里的 MODEL_URL，换网盘只改这个文件即可） */
    private String remoteModelUrl = null;
    /** 网盘提取码（latest.txt 里的 MODEL_CODE，可空） */
    private String remoteModelCode = null;
    /** 当前显示的模型弹窗；重复触发时先关掉旧的，避免叠出两个一样的弹窗。 */
    private android.app.AlertDialog modelDialog = null;
    /** 正在导入模型（解压/拷贝）时不再弹下载引导。 */
    private volatile boolean importingModels = false;
    /** 正在把模型从 /sdcard 搬到私有目录（搬的时候别让用户点开始，会报缺模型）。 */
    private volatile boolean migratingModels = false;
    private int curIdx = 0;
    private float fileProgress = 0f;
    private volatile boolean stopping = false;
    /** 每个队列文件的状态，按路径索引：队列里删条目也不会错位。 */
    private final Map<String, String> fileState =
            new java.util.concurrent.ConcurrentHashMap<>();
    /** 正在处理的那个文件的路径（队列界面高亮用）。 */
    private volatile String curFile = null;
    // ── 二级界面：队列 ──
    private FrameLayout queueOverlay;
    private LinearLayout queueList;
    private ScrollView queueScroll;
    private TextView queueSub;
    private TextView queueLive;   // 队列页里那行「当前阶段 + 运行状态」
    private final Set<String> queueSel = new java.util.LinkedHashSet<>();
    private boolean rowSwiping = false;
    private boolean askedAllFiles = false;
    private float edgeDownX = 0f, edgeDownY = 0f;
    private float qDownX = 0f, qDownY = 0f;
    private String notifiedStage = "";       // 0..1
    private ValueAnimator progressAnim;

    // ── 单位与绘制辅助 ────────────────────────────────────
    private float dp(float v) {
        return v * getResources().getDisplayMetrics().density;
    }

    private GradientDrawable round(int fill, float radiusDp, int strokeColor, float strokeDp) {
        GradientDrawable d = new GradientDrawable();
        d.setColor(fill);
        d.setCornerRadius(dp(radiusDp));
        if (strokeColor != 0) d.setStroke((int) dp(strokeDp), strokeColor);
        return d;
    }

    private View card() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(CARD, 14, CARD_LINE, 1));
        box.setPadding((int) dp(14), (int) dp(12), (int) dp(14), (int) dp(14));
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) dp(10);
        box.setLayoutParams(lp);
        box.setElevation(dp(1.5f));
        return box;
    }

    /** 卡片标题：左侧竖条 + 标题文字（照 PC 版 card_badge）。 */
    private void cardTitle(LinearLayout card, String title) {
        cardTitle(card, title, null, null);
    }

    private void cardTitle(LinearLayout card, String title, String action, Runnable onClick) {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);

        View bar = new View(this);
        bar.setBackground(round(ACCENT, 1.5f, 0, 0));
        LinearLayout.LayoutParams blp = new LinearLayout.LayoutParams((int) dp(3), (int) dp(14));
        blp.rightMargin = (int) dp(8);
        row.addView(bar, blp);

        TextView t = new TextView(this);
        t.setText(title);
        t.setTextColor(INK);
        t.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 15f);
        t.setTypeface(Typeface.DEFAULT_BOLD);
        row.addView(t);
        if (action != null) {
            row.addView(new View(this), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            TextView ab = narrowPill(action);
            ab.setOnClickListener(v -> onClick.run());
            row.addView(ab);
        }

        LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        rlp.bottomMargin = (int) dp(8);
        card.addView(row, rlp);
    }

    private TextView label(String text, int color, float sizeSp) {
        TextView v = new TextView(this);
        v.setText(text);
        v.setTextColor(color);
        v.setTextSize(TypedValue.COMPLEX_UNIT_DIP, sizeSp);
        return v;
    }

    /** 普通文字按钮（按下缩放到 0.96 + 水波纹）。 */
    private TextView pill(String text, boolean primary, float heightDp) {
        TextView b = new TextView(this);
        b.setText(text);
        b.setGravity(Gravity.CENTER);
        b.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 14f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setTextColor(primary ? 0xFFFFFFFF : INK2);
        b.setBackground(new RippleDrawable(
                ColorStateList.valueOf(primary ? 0x40FFFFFF : 0x20000000),
                round(primary ? ACCENT_DEEP : CARD, 22, primary ? 0 : CARD_LINE, 1),
                null));
        b.setPadding((int) dp(18), 0, (int) dp(18), 0);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, (int) dp(heightDp));
        b.setLayoutParams(lp);
        b.setClickable(true);
        b.setFocusable(true);
        addPressAnim(b);
        return b;
    }

    private void addPressAnim(View v) {
        v.setOnTouchListener((view, e) -> {
            switch (e.getActionMasked()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f).setDuration(120)
                            .setInterpolator(new DecelerateInterpolator()).start();
                    break;
            }
            return false;
        });
    }

    /** 卡片入场动效：透明度 + 上移，按索引交错。 */
    private void enterAnim(View v, int index) {
        v.setAlpha(0f);
        v.setTranslationY(dp(18));
        v.animate().alpha(1f).translationY(0f)
                .setStartDelay(60L * index)
                .setDuration(320)
                .setInterpolator(new DecelerateInterpolator())
                .start();
    }

    // ── 生命周期 ─────────────────────────────────────────
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Window w = getWindow();
        w.setStatusBarColor(BG);
        w.setNavigationBarColor(BG);
        if (BuildVersion.atLeast(23)) {
            w.getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        }
        outFmt = getSharedPreferences("nihon", MODE_PRIVATE).getString("fmt", "both");
        softSub = getSharedPreferences("nihon", MODE_PRIVATE).getString("soft", "off");
        outLang = getSharedPreferences("nihon", MODE_PRIVATE).getString("lang", "zh");
        asrMode = getSharedPreferences("nihon", MODE_PRIVATE).getString("asr", "fusion");
        // 赶在 buildUi / scanInputDir 覆盖之前把上次的队列抄下来
        resumeFilesRaw = getSharedPreferences(QPREF, MODE_PRIVATE).getString("files", "");
        resumeWasRunning = getSharedPreferences(QPREF, MODE_PRIVATE).getBoolean("running", false);
        setContentView(buildUi());
        scanInputDir();
        // 老版本模型在 /sdcard（FUSE），后台搬到私有目录；只搬一次。
        new Thread(this::migrateModelsIfNeeded, "model-migrate").start();
        // 上次要是被强杀/崩溃，临时文件会留在 bin/ 里，开机顺手收掉
        ui.postDelayed(this::cleanWorkFiles, 900);
        // 启动后自动检查一次更新（静默，仅在有新版本时提示）
        ui.postDelayed(() -> checkForUpdate(false), 2000);
        ui.postDelayed(this::ensureAllFilesAccess, 1200);
        // 上次队列没跑完（App 被系统杀掉 / 崩溃 / 手滑退出）→ 问要不要接着跑
        ui.postDelayed(this::promptResumeQueue, 1600);
    }

    /** 启动时扫一遍 input 目录：从电脑拷进来的文件不用再手动选。 */
    private void scanInputDir() {
        java.util.List<File> dirs = new java.util.ArrayList<>();
        dirs.add(importDir());
        File legacy = new File(appDir(), "input");
        if (legacy.isDirectory()) dirs.add(legacy);
        for (File dir : dirs) {
            scanOneDir(dir);
        }
        if (!picked.isEmpty()) refreshFiles();
    }

    private void scanOneDir(File dir) {
        File[] all = dir.listFiles();
        if (all == null) return;
        for (File f : all) {
            String n = f.getName().toLowerCase();
            if (!f.isFile()) continue;
            if (n.endsWith(".mp4") || n.endsWith(".mkv") || n.endsWith(".wav")
                    || n.endsWith(".mp3") || n.endsWith(".m4a") || n.endsWith(".webm")
                    || n.endsWith(".avi") || n.endsWith(".flac") || n.endsWith(".wmv")) {
                if (!picked.contains(f.getAbsolutePath())) picked.add(f.getAbsolutePath());
            }
        }
    }

    /** 避免直接引用 Build.VERSION_CODES 造成 lint 噪声的小包装。 */
    static class BuildVersion {
        static boolean atLeast(int v) { return android.os.Build.VERSION.SDK_INT >= v; }
    }

    private View buildUi() {
        FrameLayout root = new FrameLayout(this);
        root.setBackgroundColor(BG);

        // 可滚动主体（底部留出动作条高度）
        ScrollView scroll = new ScrollView(this);
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setPadding(0, 0, 0, (int) dp(84));
        FrameLayout.LayoutParams slp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT);
        root.addView(scroll, slp);

        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding((int) dp(14), (int) dp(6), (int) dp(14), (int) dp(6));
        // 高度设为 MATCH_PARENT：配合 ScrollView.fillViewport，日志卡才能用 weight 吃掉剩余空间
        scroll.addView(content, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        // ── 顶栏：图标 + 标题 + 副标题 ──
        LinearLayout header = new LinearLayout(this);
        header.setOrientation(LinearLayout.HORIZONTAL);
        header.setGravity(Gravity.CENTER_VERTICAL);
        header.setPadding(0, (int) dp(10), 0, (int) dp(16));

        FrameLayout icon = new FrameLayout(this);
        icon.setBackground(round(ACCENT, 12, 0, 0));
        icon.setElevation(dp(2));
        LinearLayout.LayoutParams ilp = new LinearLayout.LayoutParams((int) dp(44), (int) dp(44));
        ilp.rightMargin = (int) dp(12);
        header.addView(icon, ilp);
        TextView iconGlyph = label("字", 0xFFFFFFFF, 24f);
        iconGlyph.setGravity(Gravity.CENTER);
        icon.addView(iconGlyph, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout titles = new LinearLayout(this);
        titles.setOrientation(LinearLayout.VERTICAL);
        TextView title = label("日文字幕生成器", INK, 18f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        title.setLetterSpacing(0.01f);
        titles.addView(title);
        subtitleView = label("本地转写 · 离线翻译 · 手机 CPU 推理", INK3, 11.5f);
        LinearLayout.LayoutParams subLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        subLp.topMargin = (int) dp(3);
        titles.addView(subtitleView, subLp);
        header.addView(titles);
        // 底部动作条要留给「暂停」，所以「使用说明」移到右上角
        header.addView(new View(this), new LinearLayout.LayoutParams(0, 1, 1f));
        TextView helpTop = narrowPill("使用说明");
        helpTop.setOnClickListener(v -> showHelp());
        header.addView(helpTop, new LinearLayout.LayoutParams((int) dp(82), (int) dp(32)));
        content.addView(header);
        enterAnim(header, 0);

        // ── 卡片 1：环境 ──
        LinearLayout env = (LinearLayout) card();
        cardTitle(env, "环境");
        env.addView(infoRow("工作目录", appDir().getAbsolutePath()));
        env.addView(infoRow("输出目录",
                hasAllFiles() ? "与原片同一文件夹" : "原片旁（未授权时放 App 的 output）"));
        env.addView(statusRow());
        env.addView(modelRow());
        {
            LinearLayout ver = new LinearLayout(this);
            ver.setOrientation(LinearLayout.HORIZONTAL);
            ver.setGravity(Gravity.CENTER_VERTICAL);
            LinearLayout.LayoutParams vlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            vlp.topMargin = (int) dp(4);
            ver.setLayoutParams(vlp);
            String vn = "1.0.0";
            try { vn = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
            catch (Exception ignored) {}
            ver.addView(label("版本 " + vn + " · 检查更新", INK3, 11f), new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            // 和上面「已导入」那个主按钮同宽同高，看着才是一对
            TextView upd = pill("检查更新", false, 34);
            upd.setTextSize(TypedValue.COMPLEX_UNIT_DIP, 12.5f);
            final String _vn = vn;
            upd.setOnClickListener(v -> checkForUpdate(true));
            ver.addView(upd, new LinearLayout.LayoutParams((int) dp(96), (int) dp(34)));
            env.addView(ver);
        }
        content.addView(env);
        enterAnim(env, 1);

        // ── 卡片 2：文件列表 ──
        LinearLayout files = (LinearLayout) card();
        cardTitle(files, "文件列表", "队列", this::openQueue);
        fileBox = new LinearLayout(this);
        fileBox.setOrientation(LinearLayout.VERTICAL);
        fileBox.setBackground(round(SUNK, 10, CARD_LINE, 1));
        fileBox.setPadding((int) dp(10), (int) dp(10), (int) dp(10), (int) dp(10));
        TextView empty = label("（列表为空，点下面的按钮添加视频或音频）", INK3, 12.5f);
        fileBox.addView(empty);
        files.addView(fileBox);

        LinearLayout fileBtns = new LinearLayout(this);
        fileBtns.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams fblp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fblp.topMargin = (int) dp(10);
        // 两个按钮各占一半：三等分时中文 4 字会被 ellipsize 成「添加文…」
        TextView addBtn = narrowPill("添加文件");
        addBtn.setOnClickListener(v -> pickFiles());
        fileBtns.addView(addBtn, third());
        TextView clearBtn = narrowPill("清空列表");
        clearBtn.setOnClickListener(v -> {
            picked.clear();
            selSet.clear();
            refreshFiles();
        });
        fileBtns.addView(clearBtn, third());
        delBtn = narrowPill("删除选中");
        delBtn.setOnClickListener(v -> {
            if (selSet.isEmpty()) {
                return;
            }
            int n = selSet.size();
            picked.removeAll(selSet);
            fileState.clear();
            selSet.clear();
            refreshFiles();
            appendLog("已删除 " + n + " 个文件");
        });
        fileBtns.addView(delBtn, third());
        files.addView(fileBtns, fblp);

        // 识别模式（三个车道）
        TextView asrCap = label("识别模式", INK3, 12.5f);
        LinearLayout.LayoutParams asrCapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        asrCapLp.topMargin = (int) dp(12);
        files.addView(asrCap, asrCapLp);
        LinearLayout asrRow = new LinearLayout(this);
        asrRow.setOrientation(LinearLayout.HORIZONTAL);
        asrFusion = asrPill("融合模式", "fusion");
        asrZip = asrPill("zipformer", "zip");
        asrSv = asrPill("SenseVoice", "sv");
        asrRow.addView(asrFusion, third());
        asrRow.addView(asrZip, third());
        asrRow.addView(asrSv, third());
        LinearLayout.LayoutParams asrRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        asrRowLp.topMargin = (int) dp(6);
        files.addView(asrRow, asrRowLp);
        asrHintView = label("", INK3, 11.5f);
        LinearLayout.LayoutParams asrHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        asrHintLp.topMargin = (int) dp(4);
        files.addView(asrHintView, asrHintLp);

        // 字幕语言
        TextView langCap = label("字幕语言", INK3, 12.5f);
        LinearLayout.LayoutParams langCapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        langCapLp.topMargin = (int) dp(12);
        files.addView(langCap, langCapLp);
        LinearLayout langRow = new LinearLayout(this);
        langRow.setOrientation(LinearLayout.HORIZONTAL);
        langZh = langPill("中文翻译", "zh");
        langJa = langPill("仅日文", "ja");
        langRow.addView(langZh, half());
        langRow.addView(langJa, half());
        LinearLayout.LayoutParams langRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        langRowLp.topMargin = (int) dp(6);
        files.addView(langRow, langRowLp);

        // 输出格式（写到原片旁边的是哪几份）
        TextView fmtCap = label("输出格式", INK3, 12.5f);
        LinearLayout.LayoutParams fmtCapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fmtCapLp.topMargin = (int) dp(12);
        files.addView(fmtCap, fmtCapLp);
        LinearLayout fmtRow = new LinearLayout(this);
        fmtRow.setOrientation(LinearLayout.HORIZONTAL);
        fmtSrt = fmtPill("只要 SRT", "srt");
        fmtLrc = fmtPill("只要 LRC", "lrc");
        fmtBoth = fmtPill("两个都要", "both");
        fmtRow.addView(fmtSrt, third());
        fmtRow.addView(fmtLrc, third());
        fmtRow.addView(fmtBoth, third());
        LinearLayout.LayoutParams fmtRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        fmtRowLp.topMargin = (int) dp(6);
        files.addView(fmtRow, fmtRowLp);

        // 字幕封装：默认只有字幕文件；开了就额外存一个带字幕的 mkv
        TextView softCap = label("字幕封装", INK3, 12.5f);
        LinearLayout.LayoutParams softCapLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        softCapLp.topMargin = (int) dp(12);
        files.addView(softCap, softCapLp);
        LinearLayout softRow = new LinearLayout(this);
        softRow.setOrientation(LinearLayout.HORIZONTAL);
        softOff = softPill("外挂字幕", "off");
        softOn = softPill("软字幕（mkv）", "on");
        softRow.addView(softOff, half());
        softRow.addView(softOn, half());
        LinearLayout.LayoutParams softRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        softRowLp.topMargin = (int) dp(6);
        files.addView(softRow, softRowLp);
        softHint = label("", INK3, 11.5f);
        LinearLayout.LayoutParams softHintLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        softHintLp.topMargin = (int) dp(4);
        files.addView(softHint, softHintLp);
        applySoft();
        applyFmt();
        applyLang();
        applyAsr();
        content.addView(files);
        enterAnim(files, 2);

        // ── 卡片 3：进度 ──
        LinearLayout prog = (LinearLayout) card();
        cardTitle(prog, "进度");
        // 阶段行右侧加计时：长任务跑起来用户看到秒在跳，就不会以为卡死了
        LinearLayout stageRow = new LinearLayout(this);
        stageRow.setOrientation(LinearLayout.HORIZONTAL);
        stageRow.setGravity(Gravity.CENTER_VERTICAL);
        stageView = label("当前阶段　—", INK2, 12.5f);
        stageRow.addView(stageView, new LinearLayout.LayoutParams(0,
                ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        elapsedView = label("已用 00:00", INK3, 12f);
        stageRow.addView(elapsedView);
        prog.addView(stageRow);

        progressTrack = new FrameLayout(this);
        progressTrack.setBackground(round(SUNK, 5, CARD_LINE, 1));
        LinearLayout.LayoutParams ptlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(8));
        ptlp.topMargin = (int) dp(10);
        ptlp.bottomMargin = (int) dp(8);
        prog.addView(progressTrack, ptlp);

        progressFill = new View(this);
        progressFill.setBackground(round(ACCENT, 5, 0, 0));
        progressTrack.addView(progressFill, new FrameLayout.LayoutParams(0,
                ViewGroup.LayoutParams.MATCH_PARENT));

        LinearLayout prow = new LinearLayout(this);
        prow.setOrientation(LinearLayout.HORIZONTAL);
        prow.setGravity(Gravity.CENTER_VERTICAL);
        percentView = label("0%", ACCENT_INK, 13f);
        percentView.setTypeface(Typeface.DEFAULT_BOLD);
        prow.addView(percentView);
        LinearLayout.LayoutParams plp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        TextView dummy = label("", INK3, 12f);
        prow.addView(dummy, plp);
        prog.addView(prow);

        LinearLayout.LayoutParams actLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actLp.topMargin = (int) dp(6);
        actionView = label("当前动作　—", INK3, 11.5f);
        // 这两行内容经常比一屏宽（识别速度、融合翻译的长描述），
        // 单行省略号会被截掉一半，改成最多两行、自动折行，卡片跟着变高。
        actionView.setMaxLines(2);
        prog.addView(actionView, actLp);

        // 「运行状态」行：跑任务时每 2 秒刷一次 CPU 频率 / 模型 tok/s / 占用核数
        LinearLayout.LayoutParams statRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statRowLp.topMargin = (int) dp(4);
        statView = label("运行状态　—", INK3, 11f);
        statView.setMaxLines(2);
        prog.addView(statView, statRowLp);
        content.addView(prog);
        enterAnim(prog, 3);

        // ── 卡片 4：字幕预览（边跑边出，只留最新几句）──
        LinearLayout prevCard = (LinearLayout) card();
        cardTitle(prevCard, "字幕预览");
        previewScroll = new ScrollView(this);
        previewScroll.setBackground(round(SUNK, 10, CARD_LINE, 1));
        previewScroll.setPadding((int) dp(10), (int) dp(8), (int) dp(10), (int) dp(8));
        previewView = label("还没开始跑。开跑后这里会一句句冒出来。", INK3, 11.5f);
        previewView.setLineSpacing(dp(4), 1f);
        previewScroll.addView(previewView);
        prevCard.addView(previewScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(150)));
        content.addView(prevCard, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        enterAnim(prevCard, 4);

        // ── 卡片 5：日志 ──
        LinearLayout logs = (LinearLayout) card();
        cardTitle(logs, "运行日志", "导出", this::exportLog);
        logScroll = new ScrollView(this);
        logScroll.setBackground(round(SUNK, 10, CARD_LINE, 1));
        logScroll.setPadding((int) dp(10), (int) dp(8), (int) dp(10), (int) dp(8));
        logView = label("就绪。点「添加文件」选好视频，再点下方「开始处理」。", INK3, 11.5f);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(dp(3), 1f);
        logScroll.addView(logView);
        logScroll.setMinimumHeight((int) dp(120));
        // 以前这里是 0 + weight=1，靠「吃掉剩余高度」撑开。但文件列表卡片加上
        // 「字幕语言 / 输出格式」两组按钮后，剩余高度被算成 0，
        // 结果标题「运行日志 + 导出」被挤出可视区（实测 dump 里整块消失）。
        // 改成固定高度，滚动由外层 ScrollView 负责，稳定且不会被裁。
        logs.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(210)));
        content.addView(logs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        enterAnim(logs, 5);

        // ── 底部固定动作条 ──
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BG);
        bar.setPadding((int) dp(14), (int) dp(8), (int) dp(14), (int) dp(12));
        bar.setElevation(dp(6));

        // 三个按钮等宽等高等权重：以前 WRAP_CONTENT 导致「停止」比「开始处理」窄一截，视觉不齐
        startBtn = pill("开始处理", true, 46);
        startBtn.setOnClickListener(v -> start());
        bar.addView(startBtn, new LinearLayout.LayoutParams(0, (int) dp(46), 1f));

        pauseBtn = pill("暂停", false, 46);
        LinearLayout.LayoutParams pzlp = new LinearLayout.LayoutParams(0, (int) dp(46), 1f);
        pzlp.leftMargin = (int) dp(8);
        pauseBtn.setOnClickListener(v -> togglePause());
        pauseBtn.setAlpha(0.4f);
        pauseBtn.setEnabled(false);
        bar.addView(pauseBtn, pzlp);

        stopBtn = pill("停止", false, 46);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(0, (int) dp(46), 1f);
        stlp.leftMargin = (int) dp(8);
        stopBtn.setOnClickListener(v -> stop());
        stopBtn.setAlpha(0.4f);
        stopBtn.setEnabled(false);
        bar.addView(stopBtn, stlp);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        root.addView(bar, blp);

        // ── 二级界面：队列浮层（默认藏起来，从左往右划打开）──
        queueOverlay = buildQueueOverlay();
        queueOverlay.setVisibility(View.GONE);
        root.addView(queueOverlay, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        return root;
    }

    // ── 二级界面：队列（从左往右划打开）──────────────────────
    /** 屏幕左侧往右划 → 打开队列页。用 dispatchTouchEvent 抓，避免和列表点击打架。 */
    @Override
    public boolean dispatchTouchEvent(MotionEvent ev) {
        if (queueOverlay == null) {
            return super.dispatchTouchEvent(ev);
        }
        if (queueOverlay.getVisibility() == View.VISIBLE) {
            // 队列页里横向划一下（往左或往右）就收起来；正在拖已选行左划移除时除外
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    qDownX = ev.getX();
                    qDownY = ev.getY();
                    break;
                case MotionEvent.ACTION_UP: {
                    float dx = ev.getX() - qDownX;
                    float dy = ev.getY() - qDownY;
                    if (!rowSwiping && Math.abs(dx) > dp(90)
                            && Math.abs(dy) < Math.abs(dx) * 0.7f) {
                        closeQueue();
                    }
                    break;
                }
                default:
                    break;
            }
            return super.dispatchTouchEvent(ev);
        }
        switch (ev.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                edgeDownX = ev.getX();
                edgeDownY = ev.getY();
                break;
            case MotionEvent.ACTION_UP: {
                float dx = ev.getX() - edgeDownX;
                float dy = ev.getY() - edgeDownY;
                // 系统返回手势会吃掉左边缘的右划，所以不限定起手位置，只要横向右划够长就开
                if (dx > dp(110) && Math.abs(dy) < dx * 0.7f) {
                    openQueue();
                }
                break;
            }
            default:
                break;
        }
        return super.dispatchTouchEvent(ev);
    }

    @Override
    public void onBackPressed() {
        if (queueOverlay != null && queueOverlay.getVisibility() == View.VISIBLE) {
            closeQueue();
            return;
        }
        super.onBackPressed();
    }

    private void openQueue() {
        if (queueOverlay == null) return;
        queueSel.clear();
        refreshQueue();
        syncQueueLive();
        queueOverlay.animate().cancel();
        // 和手势同向：从左边缘往右划 → 队列页从左边滑进来
        queueOverlay.setTranslationX(-getResources().getDisplayMetrics().widthPixels);
        queueOverlay.setVisibility(View.VISIBLE);
        queueOverlay.animate().translationX(0f).setDuration(220)
                .setInterpolator(new DecelerateInterpolator()).start();
        ui.removeCallbacks(queueTick);
        ui.postDelayed(queueTick, 500L);
    }

    private void closeQueue() {
        if (queueOverlay == null || queueOverlay.getVisibility() != View.VISIBLE) return;
        ui.removeCallbacks(queueTick);
        queueOverlay.animate().cancel();
        queueOverlay.animate().translationX(-getResources().getDisplayMetrics().widthPixels)
                .setDuration(180L)
                .setListener(new AnimatorListenerAdapter() {
                    @Override
                    public void onAnimationEnd(Animator a) {
                        if (queueOverlay != null) {
                            queueOverlay.setVisibility(View.GONE);
                            queueOverlay.animate().setListener(null);
                        }
                    }
                }).start();
    }

    /** 队列页开着时定期刷新（处理中每行状态会变）。 */
    private final Runnable queueTick = new Runnable() {
        @Override
        public void run() {
            if (queueOverlay == null || queueOverlay.getVisibility() != View.VISIBLE) return;
            // 手势进行中或刚松手时别重建行（会打断滑动）
            if (!rowSwiping && System.currentTimeMillis() - queueTouchAt > 800L) refreshQueueIfChanged();
            ui.postDelayed(this, 900L);
        }
    };

    private FrameLayout buildQueueOverlay() {
        FrameLayout overlay = new FrameLayout(this);
        overlay.setBackgroundColor(BG);
        overlay.setClickable(true);
        overlay.setElevation(dp(12));

        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        col.setPadding((int) dp(14), (int) dp(20), (int) dp(14), (int) dp(12));

        LinearLayout head = new LinearLayout(this);
        head.setOrientation(LinearLayout.HORIZONTAL);
        head.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = label("队列", INK, 20f);
        title.setTypeface(Typeface.DEFAULT_BOLD);
        head.addView(title);
        queueSub = label("", INK3, 11.5f);
        LinearLayout.LayoutParams qslp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        qslp.leftMargin = (int) dp(10);
        head.addView(queueSub, qslp);
        TextView close = narrowPill("关闭");
        close.setOnClickListener(v -> closeQueue());
        head.addView(close);
        col.addView(head);

        // 队列页也要看得到「正在干什么」：当前阶段 + 运行状态（段速/补车道/模型加载），
        // 以前只有主界面进度卡有，打开队列就两眼一抹黑。
        queueLive = label("空闲 · 点「开始处理」开始，进度会在这里实时刷新", INK2, 12f);
        queueLive.setBackground(round(CARD, 10, CARD_LINE, 1));
        queueLive.setPadding((int) dp(12), (int) dp(10), (int) dp(12), (int) dp(10));
        LinearLayout.LayoutParams qllp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        qllp.topMargin = (int) dp(10);
        col.addView(queueLive, qllp);

        TextView tip = label("上下滑动看全部 · 点一下或长按选中 · 选中后往左划移除 · 左右划空白处收起", INK3, 11f);
        LinearLayout.LayoutParams tipLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        tipLp.topMargin = (int) dp(8);
        tipLp.bottomMargin = (int) dp(10);
        col.addView(tip, tipLp);

        queueScroll = new ScrollView(this);
        queueScroll.setBackground(round(SUNK, 12, CARD_LINE, 1));
        queueList = new LinearLayout(this);
        queueList.setOrientation(LinearLayout.VERTICAL);
        queueList.setPadding((int) dp(10), (int) dp(10), (int) dp(10), (int) dp(10));
        queueScroll.addView(queueList, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        col.addView(queueScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        LinearLayout foot = new LinearLayout(this);
        foot.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams flp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        flp.topMargin = (int) dp(10);
        TextView rmSel = narrowPill("移除选中");
        rmSel.setOnClickListener(v -> {
            if (queueSel.isEmpty()) {
                toast("先长按选中要移除的任务");
                return;
            }
            int n = 0;
            for (String p : new ArrayList<>(queueSel)) {
                if (removeFromQueue(p, false)) n++;
            }
            queueSel.clear();
            refreshQueue();
            refreshFiles();
            if (n > 0) appendLog("已从队列移除 " + n + " 个");
        });
        foot.addView(rmSel, third());
        TextView startNow = narrowPill("开始处理");
        startNow.setOnClickListener(v -> {
            closeQueue();
            ui.postDelayed(this::start, 240L);
        });
        foot.addView(startNow, third());
        TextView stopNow = narrowPill("停止");
        stopNow.setOnClickListener(v -> stop());
        foot.addView(stopNow, third());
        col.addView(foot, flp);

        overlay.addView(col, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));
        return overlay;
    }

    /** 重建队列列表：全部任务 + 各自状态；长按多选、选中后往左划移除。 */
    private void refreshQueue() {
        if (queueList == null) return;
        queueList.removeAllViews();
        int total = picked.size();
        int done = 0;
        for (String p : picked) if ("完成".equals(stateOf(p))) done++;
        if (queueSub != null) {
            queueSub.setText("共 " + total + " 个 · 完成 " + done
                    + (queueSel.isEmpty() ? "" : " · 已选 " + queueSel.size()));
        }
        if (total == 0) {
            queueList.addView(label("队列是空的，回主界面点「添加文件」", INK3, 12.5f));
            queueSig = queueSignature();
            return;
        }
        for (int k = 0; k < total; k++) {
            final String path = picked.get(k);
            final String st = stateOf(path);
            final boolean cur = busy && path.equals(curFile);
            final boolean sel = queueSel.contains(path);

            final LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.VERTICAL);
            row.setPadding((int) dp(12), (int) dp(10), (int) dp(12), (int) dp(10));
            row.setBackground(round(sel ? ACCENT_SOFT : CARD, 10,
                    sel ? ACCENT : CARD_LINE, sel ? 1.5f : 1f));
            LinearLayout.LayoutParams rlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            rlp.bottomMargin = (int) dp(8);
            row.setLayoutParams(rlp);

            LinearLayout top = new LinearLayout(this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);
            top.addView(label((k + 1) + ".", ACCENT, 12f), new LinearLayout.LayoutParams(
                    (int) dp(24), ViewGroup.LayoutParams.WRAP_CONTENT));
            TextView name = label(new File(path).getName(), INK, 13.5f);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            name.setTypeface(Typeface.DEFAULT_BOLD);
            top.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            stlp.leftMargin = (int) dp(6);
            top.addView(label(percentText(st, cur), stateColor(st, cur), 11.5f), stlp);
            row.addView(top);

            if (cur) {
                FrameLayout track = new FrameLayout(this);
                track.setBackground(round(0xFFE9E3D6, 4, 0, 0));
                LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT, (int) dp(5));
                tlp.topMargin = (int) dp(8);
                row.addView(track, tlp);
                View fill = new View(this);
                fill.setBackground(round(ACCENT, 4, 0, 0));
                float f = Math.max(0.02f, Math.min(1f, fileProgress));
                int w = (int) (getResources().getDisplayMetrics().widthPixels * f * 0.82f);
                track.addView(fill, new FrameLayout.LayoutParams(w,
                        ViewGroup.LayoutParams.MATCH_PARENT));
            }

            // 选中只改这一行的样式，不重建整个列表（重建会把手势流打断，滑动就失效了）
            row.setOnClickListener(v -> toggleQueueSel(path, row));
            row.setOnLongClickListener(v -> {
                if (rowSwiping) return true;
                toggleQueueSel(path, row);
                return true;
            });
            final View rv = row;
            row.setOnTouchListener((v, e) -> {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        swipeStartX = e.getRawX();
                        rowSwiping = false;
                        queueTouchAt = System.currentTimeMillis();
                        return false;
                    case MotionEvent.ACTION_MOVE: {
                        if (!queueSel.contains(path)) return false;
                        float dx = e.getRawX() - swipeStartX;
                        if (dx < -dp(10)) {
                            rowSwiping = true;
                            rv.setTranslationX(dx);
                        }
                        return false;
                    }
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL: {
                        float dx = e.getRawX() - swipeStartX;
                        if (rowSwiping && dx < -dp(72)) {
                            flingRemove(path, rv);
                        } else if (rowSwiping) {
                            rv.animate().translationX(0f).setDuration(140L).start();
                        }
                        rowSwiping = false;
                        queueTouchAt = System.currentTimeMillis();
                        return false;
                    }
                    default:
                        return false;
                }
            });
            queueList.addView(row);
        }
        queueSig = queueSignature();
    }

    /** 切换某一行选中状态：只改这一行外观 + 顶部统计，不重建列表。 */
    private void toggleQueueSel(String path, View row) {
        if (queueSel.contains(path)) queueSel.remove(path);
        else queueSel.add(path);
        boolean on = queueSel.contains(path);
        row.setBackground(round(on ? ACCENT_SOFT : CARD, 10,
                on ? ACCENT : CARD_LINE, on ? 1.5f : 1f));
        row.setPadding((int) dp(12), (int) dp(10), (int) dp(12), (int) dp(10));
        updateQueueSub();
    }

    private void updateQueueSub() {
        if (queueSub != null) {
            int total = picked.size(), done = 0;
            for (String p : picked) if ("完成".equals(stateOf(p))) done++;
            queueSub.setText("共 " + total + " 个 · 完成 " + done
                    + (queueSel.isEmpty() ? "" : " · 已选 " + queueSel.size()));
        }
        // 让定时刷新认为“没变化”，别在用户操作期间重建行
        queueSig = queueSignature();
    }

    /** 把主界面的「当前阶段 / 运行状态」同步到队列页那行提示。 */
    private void syncQueueLive() {
        if (queueLive == null) return;
        if (!busy) {
            int done = 0;
            for (String p : picked) if ("完成".equals(stateOf(p))) done++;
            queueLive.setText(picked.isEmpty()
                    ? "空闲 · 队列是空的，回主界面点「添加文件」"
                    : "空闲 · 共 " + picked.size() + " 个，完成 " + done + " 个");
            return;
        }
        String stage = stageView == null ? "" : stageView.getText().toString().trim();
        String stat = statView == null ? "" : statView.getText().toString().trim();
        StringBuilder b = new StringBuilder();
        if (!stage.isEmpty()) b.append(stage);
        if (!stat.isEmpty()) {
            if (b.length() > 0) b.append('\n');
            b.append(stat);
        }
        queueLive.setText(b.length() == 0 ? "处理中…" : b.toString());
    }

    private float swipeStartX = 0f;
    private long queueTouchAt = 0L;
    private String queueSig = null;

    /** 队列状态的轻量指纹：没变化就不用重建行。 */
    private String queueSignature() {
        StringBuilder sb = new StringBuilder();
        sb.append(picked.size()).append('|').append(queueSel.size()).append('|')
                .append(curFile).append('|').append((int) (fileProgress * 40)).append('|');
        for (String p : picked) sb.append(stateOf(p)).append(',');
        return sb.toString();
    }

    private void refreshQueueIfChanged() {
        String sig = queueSignature();
        if (sig.equals(queueSig)) return;
        refreshQueue();
    }

    private float rv_width() {
        return getResources().getDisplayMetrics().widthPixels;
    }

    private void flingRemove(final String path, final View row) {
        row.animate().translationX(-rv_width())
                .alpha(0f).setDuration(180L)
                .withEndAction(() -> {
                    if (removeFromQueue(path, true)) {
                        refreshQueue();
                        refreshFiles();
                    }
                }).start();
    }

    /** 从队列移除一条；正在处理的那个不动。 */
    private boolean removeFromQueue(String path, boolean log) {
        if (!picked.contains(path)) return false;
        if (busy && path.equals(curFile)) {
            toast("这个正在处理，先点「停止」再移除");
            return false;
        }
        picked.remove(path);
        fileState.remove(path);
        queueSel.remove(path);
        if (log) appendLog("已从队列移除：" + new File(path).getName());
        return true;
    }

    private String stateOf(String path) {
        String s = fileState.get(path);
        return s == null ? "待处理" : s;
    }

    private String percentText(String st, boolean cur) {
        if (cur) {
            int pct = Math.round(Math.max(0f, Math.min(1f, fileProgress)) * 100f);
            return "处理中 " + pct + "%";
        }
        return st;
    }

    private int stateColor(String st, boolean cur) {
        if (cur || "处理中".equals(st)) return ACCENT;
        if ("完成".equals(st)) return OK_GREEN;
        if ("失败".equals(st)) return WARN;
        return INK3;
    }

    // ── 所有文件访问（字幕写回原片旁边要用）───────────────────
    private boolean hasAllFiles() {
        if (Build.VERSION.SDK_INT >= 30) {
            try {
                return Environment.isExternalStorageManager();
            } catch (Exception e) {
                return false;
            }
        }
        try {
            return checkSelfPermission(android.Manifest.permission.WRITE_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        } catch (Exception e) {
            return false;
        }
    }

    /** 没授权时引导一次；不拦着用，只是字幕改放 App 的 output 目录。 */
    private void ensureAllFilesAccess() {
        if (hasAllFiles() || askedAllFiles) return;
        askedAllFiles = true;
        if (Build.VERSION.SDK_INT >= 30) {
            new android.app.AlertDialog.Builder(this)
                    .setTitle("需要「所有文件访问」")
                    .setMessage("授予后，生成的 .srt / .lrc 会直接写回原视频旁边。\n"
                            + "不授权也能正常用，字幕会放在 App 自己的 output 目录。")
                    .setPositiveButton("去授权", (d, w) -> {
                        try {
                            startActivity(new Intent(
                                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:" + getPackageName())));
                        } catch (Exception e) {
                            try {
                                startActivity(new Intent(
                                        android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION));
                            } catch (Exception ignored) {
                            }
                        }
                    })
                    .setNegativeButton("暂不", null)
                    .show();
        } else {
            try {
                requestPermissions(new String[]{android.Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1003);
            } catch (Exception ignored) {
            }
        }
    }

    /** 使用说明弹层：自绘卡片，配色/圆角与主界面一致。 */
    private void showHelp() {
        // 路径写成相对形式：完整路径太长，窄屏上会被折行，反而不好认
        String txt =
                "【第一次用】\n"
                + "模型由 App 自己保管，导入一次即可。\n"
                + "推荐直接选模型包 asmr-models.zip\n"
                + "（约 1.5 GB，一次装齐，三种模式都能用）。\n"
                + "单独装也行，但要按想用的模式挑：\n"
                + "　· 融合 / zipformer 模式：\n"
                + "　　要 zipformer-ja/ 那 4 个文件\n"
                + "　　（encoder.int8.onnx、decoder.onnx、\n"
                + "　　　joiner.onnx、tokens.txt）\n"
                + "　· SenseVoice 模式：要 model.int8.onnx\n"
                + "　· 不管哪种模式，都要 hy.gguf（翻译用）\n"
                + "点「导入模型」选 zip 最省事。\n"
                + "（模型存在 App 私有目录，不用手动放、\n"
                + "　也不会被清理软件删）\n"
                + "\n"
                + "【放片子】\n"
                + "点「添加文件」选视频或音频\n"
                + "（mp4 / mkv / wmv / mp3 / wav 都行），\n"
                + "或者用数据线拷进 input 目录，重开 App 会自动扫出来。\n"
                + "\n"
                + "【队列】\n"
                + "屏幕从左往右划（或点「文件列表」右上角「队列」）\n"
                + "打开整屏队列：\n"
                + "上下滑动看全部任务；点一下或长按选中，\n"
                + "选中后往左划即从队列移除；\n"
                + "在空白处左右划一下收起队列页。\n"
                + "\n"
                + "【识别模式】\n"
                + "三选一，默认「融合模式」：\n"
                + "　· SenseVoice：速度快、覆盖广，\n"
                + "　　ASMR 里各种音效段都能碰到，但长难句、\n"
                + "　　同音字容易听错。\n"
                + "　· zipformer：认得最准，整句更通顺，\n"
                + "　　但安静段、纯音效段可能整句漏掉。\n"
                + "　· 融合模式（默认）：两个都跑，\n"
                + "　　zipformer 打底、SenseVoice 补漏，\n"
                + "　　又全又准，代价是识别阶段慢一点。\n"
                + "选错也不影响出字幕，换一个模式重跑就行。\n\n"
                + "【开始处理】\n"
                + "点「开始处理」后四步全自动，不用管：\n"
                + "　1/4 提取音频　2/4 语音识别\n"
                + "　3/4 起翻译服务　4/4 翻译并写字幕\n"
                + "切到别的 App 也会继续跑，进度会显示在通知栏。\n"
                + "\n"
                + "【字幕预览】\n"
                + "进度卡下面那张卡会边跑边出内容：\n"
                + "识别阶段先显示日文原文，\n"
                + "翻译阶段一句句换成中文，只留最新几句。\n"
                + "全片跑完以生成的字幕文件为准。\n"
                + "\n"
                + "【暂停 / 继续】\n"
                + "处理中随时可以点「暂停」，处理进程被原地冻结，\n"
                + "已识别的、已翻译的都留在内存里。\n"
                + "点「继续」从停下的地方接着跑，不丢进度、不重跑。\n"
                + "「停止」才是真正中断当前任务。\n"
                + "\n"
                + "【别被杀掉】\n"
                + "长任务怕系统清后台，建议先设一次：\n"
                + "· 小米 / 红米：省电策略 → 无限制，\n"
                + "　最近任务卡片下拉加锁\n"
                + "· 华为 / 荣耀：应用启动管理 → 关「自动管理」，\n"
                + "　打开自启动和后台活动，卡片下拉加锁\n"
                + "· OPPO / 一加 / 真我：电池 → 应用耗电管理，\n"
                + "　允许后台运行，卡片长按 → 锁定\n"
                + "· vivo / iQOO：电池 → 后台耗电管理，\n"
                + "　允许后台高耗电；i 管家 → 自启动 → 允许\n"
                + "· 三星：电池 → 后台使用限制，\n"
                + "　永不休眠的应用 → 加上本 App\n"
                + "· 原生安卓：应用 → 电池 → 不受限制\n"
                + "跑的时候别点「清理后台」和一键加速。\n"
                + "真被杀了也不丢进度，见下一段。\n"
                + "\n"
                + "【意外退出】\n"
                + "队列没跑完就被系统清理或自己崩了，\n"
                + "下次打开 App 会弹「继续上次队列」，\n"
                + "点「继续」接着处理，已完成的文件自动跳过。\n"
                + "\n"
                + "【拿字幕】\n"
                + "字幕直接写在原片所在文件夹；\n"
                + "首次会请你授予「所有文件访问」，\n"
                + "没授权就放在 App 的 output 目录。\n"
                + "每个片子按「输出格式」生成（默认两个都要）：\n"
                + "　<原文件名>.chs.srt　（播放器外挂字幕）\n"
                + "　<原文件名>.chs.lrc　（音乐播放器逐行歌词）\n"
                + "和视频放一起，用播放器加载就能看。\n"
                + "\n"
                + "【软字幕】\n"
                + "「字幕封装」选「软字幕（mkv）」时，\n"
                + "不再单独留字幕文件，而是把字幕嵌进视频，\n"
                + "在原片旁另存一个 <原文件名>.mkv，\n"
                + "播放器里能开关字幕。\n"
                + "不重新编码，体积和原片差不多。\n"
                + "做完会问你要不要删掉原文件。\n"
                + "（只有 mkv 能装字幕，mp4 装不了）\n"
                + "（字幕/输入目录在 Android/data/com.nihonsubs/files 下；\n"
                + "　模型在 App 私有目录，不用手管）\n"
                + "\n"
                + "【耗时参考】\n"
                + "90 秒的片子约 31 秒；16 分钟约 6~7 分钟；91 分钟约 12 分钟。\n"
                + "全程离线，不联网。\n\n"
                + "【常见问题】\n"
                + "· 字幕条数少：ASMR 片子环境音多，纯音效段会被跳过，正常\n"
                + "· 准确度：手机上用的是 int8 量化模型，比 PC 版略逊属正常\n"
                + "· 卡住不动：点「暂停」再「继续」；还不行就点「停止」重来";

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setBackground(round(CARD, 14, CARD_LINE, 1));
        box.setPadding((int) dp(16), (int) dp(14), (int) dp(16), (int) dp(14));

        TextView head = label("使用说明", INK, 15f);
        head.setTypeface(Typeface.DEFAULT_BOLD);
        box.addView(head);

        ScrollView sc = new ScrollView(this);
        TextView body = label(txt, INK2, 12f);
        body.setLineSpacing(dp(4.5f), 1f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f);
        slp.topMargin = (int) dp(10);
        sc.addView(body);
        box.addView(sc, slp);

        TextView ok = pill("知道了", true, 44);
        LinearLayout.LayoutParams olp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        olp.topMargin = (int) dp(12);
        olp.gravity = Gravity.END;
        box.addView(ok, olp);

        FrameLayout wrap = new FrameLayout(this);
        // 底部留一点空隙即可：留太多会把卡片压矮，最后两行要滚动才看得见
        wrap.setPadding((int) dp(16), (int) dp(28), (int) dp(16), (int) dp(28));
        wrap.addView(box, new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT));

        final android.app.AlertDialog dlg = new android.app.AlertDialog.Builder(this)
                .setView(wrap).create();
        if (dlg.getWindow() != null) {
            dlg.getWindow().setBackgroundDrawable(
                    new android.graphics.drawable.ColorDrawable(Color.TRANSPARENT));
            dlg.getWindow().setLayout(
                    ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        }
        // 首次打开必须先滑到底才让点：内容没看完就关掉，等于没写说明。
        // （内容不足一屏时直接解锁，不会把按钮卡死。）
        ok.setText("往下滑看完才能继续");
        ok.setAlpha(0.45f);
        ok.setEnabled(false);
        final Runnable unlock = () -> {
            ok.setText("知道了");
            ok.setEnabled(true);
            ok.setAlpha(1f);
        };
        sc.setOnScrollChangeListener((v, sx, sy, osx, osy) -> {
            View child = sc.getChildAt(0);
            if (child == null) {
                return;
            }
            int max = Math.max(0, child.getHeight() - sc.getHeight());
            if (sy >= max - dp(6)) {
                unlock.run();
            }
        });
        sc.post(() -> {
            View child = sc.getChildAt(0);
            if (child != null && child.getHeight() <= sc.getHeight() + dp(4)) {
                unlock.run();
            }
        });
        ok.setOnClickListener(v -> {
            if (!ok.isEnabled()) {
                return;
            }
            dlg.dismiss();
        });
        dlg.show();
    }

    private LinearLayout infoRow(String key, String value) {
        LinearLayout col = new LinearLayout(this);
        col.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        lp.bottomMargin = (int) dp(6);
        col.setLayoutParams(lp);
        col.addView(label(key, INK3, 11f));
        TextView v = label(value, INK2, 12.5f);
        v.setMaxLines(2);
        col.addView(v);
        return col;
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 用户可能刚用数据线把模型拷进 models 目录再切回来，这里重新判一次
        refreshModelRow();
    }

    private LinearLayout statusRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) dp(2), 0, 0);

        View dot = new View(this);
        dot.setBackground(round(OK_GREEN, 4f, 0, 0));
        LinearLayout.LayoutParams dlp = new LinearLayout.LayoutParams((int) dp(8), (int) dp(8));
        dlp.rightMargin = (int) dp(6);
        row.addView(dot, dlp);

        row.addView(label("设备状态：CPU 推理（arm64，8 核）", INK2, 12.5f));
        return row;
    }

    /**
     * 环境卡里的「模型」一行：左边状态文字，右边「导入模型」按钮。
     * 两个模型都不缺时按钮变灰、改文案为「已导入」，不可再点 —— 避免用户重复导入。
     */
    private LinearLayout modelRow() {
        LinearLayout row = new LinearLayout(this);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) dp(8), 0, 0);

        modelStateView = label("", INK3, 12f);
        LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        row.addView(modelStateView, slp);

        modelBtn = pill("导入模型", true, 34);
        modelBtn.setOnClickListener(v -> promptModelDownload());
        row.addView(modelBtn, new LinearLayout.LayoutParams((int) dp(96), (int) dp(34)));

        // 建完 UI 之后刷新一次（此时 modelDir 已可读）
        ui.post(this::refreshModelRow);
        return row;
    }

    /** 按模型目录的实际内容刷新那一行（导入成功后也要调一次）。 */
    private void refreshModelRow() {
        if (modelStateView == null || modelBtn == null) return;
        File md = modelDir();
        boolean onnx = new File(md, "model.int8.onnx").isFile();
        boolean gguf = new File(md, "hy.gguf").isFile();

        if (onnx && gguf) {
            long onnxMb = new File(md, "model.int8.onnx").length() / (1024 * 1024);
            if (onnxMb > 500) {
                // 常见事故：把 hy.gguf 选成了 model.int8.onnx
                modelStateView.setText("识别模型异常（" + onnxMb + " MB，应为约 230 MB），请重新导入");
            } else {
                modelStateView.setText("模型已导入");
            }
            modelStateView.setTextColor(OK_GREEN);
            modelBtn.setText("已导入");
            // 保持可点：模型文件不对时可以重新导入覆盖
            modelBtn.setEnabled(true);
            modelBtn.setAlpha(1f);
        } else {
            if (!onnx && !gguf) {
                modelStateView.setText("未检测到模型");
            } else if (!onnx) {
                modelStateView.setText("缺语音识别模型 model.int8.onnx");
            } else {
                modelStateView.setText("缺翻译模型 hy.gguf");
            }
            modelStateView.setTextColor(WARN);
            modelBtn.setText("导入模型");
            modelBtn.setEnabled(true);
            modelBtn.setAlpha(1f);
        }
    }

    // ── 文件选择 ─────────────────────────────────────────
    private void pickFiles() {
        if (!hasAllFiles()) ensureAllFilesAccess();
        selSet.clear();
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true);
        startActivityForResult(i, REQ_PICK);
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        // 模型导入走同一个回调（REQ_MODEL），和「添加文件」的 REQ_PICK 区分开
        if (req == REQ_MODEL) {
            if (res != RESULT_OK || data == null || data.getData() == null) {
                appendLog("[模型] 已取消导入");
                pendingModelName = null;
                return;
            }
            final Uri mUri = data.getData();
            importingModels = true;
            new Thread(() -> {
                try {
                    copyModel(mUri);
                } finally {
                    importingModels = false;
                }
            }, "model-import").start();
            return;
        }
        if (req != REQ_PICK || res != RESULT_OK || data == null) return;
        if (data.getClipData() != null) {
            int n = data.getClipData().getItemCount();
            for (int k = 0; k < n; k++) {
                addUri(data.getClipData().getItemAt(k).getUri());
            }
        } else if (data.getData() != null) {
            addUri(data.getData());
        }
        refreshFiles();
    }

    private void addUri(Uri uri) {
        String path = uriToPath(uri);
        if (path != null && !picked.contains(path)) picked.add(path);
    }

    /**
     * content:// → 队列里用的路径。
     * 有「所有文件访问」时优先用原文件真实路径（这样字幕能写回原片旁边，也不用复制几个 G）；
     * 拿不到真实路径再退回老办法：复制进 App 的 input 目录。
     */
    private String uriToPath(Uri uri) {
        String real = realPathOf(uri);
        if (real != null) {
            File f = new File(real);
            if (f.isFile() && f.canRead() && hasAllFiles()) return f.getAbsolutePath();
        }
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int idx = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String name = c.getString(idx);
                    File dst = new File(importDir(), name);
                    try (InputStream in = getContentResolver().openInputStream(uri);
                         OutputStream out = new FileOutputStream(dst)) {
                        byte[] buf = new byte[1 << 16];
                        int r;
                        while ((r = in.read(buf)) > 0) out.write(buf, 0, r);
                    }
                    return dst.getAbsolutePath();
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** content:// 反查真实路径。只认外部存储提供者的几种 id 形态，认不出返回 null。 */
    private String realPathOf(Uri uri) {
        try {
            String id = null;
            try {
                id = DocumentsContract.getDocumentId(uri);
            } catch (Exception ignored) {
            }
            if (id != null) {
                if (id.startsWith("raw:")) return id.substring(4);
                int colon = id.indexOf(':');
                if (colon > 0) {
                    String vol = id.substring(0, colon);
                    String rel = id.substring(colon + 1);
                    if ("primary".equalsIgnoreCase(vol)) return "/storage/emulated/0/" + rel;
                    if ("home".equalsIgnoreCase(vol)) return "/storage/emulated/0/Documents/" + rel;
                    if (vol.matches("[0-9A-Fa-f]{4}-[0-9A-Fa-f]{4}")) return "/storage/" + vol + "/" + rel;
                }
                if (id.startsWith("msf:")) {
                    try (Cursor c = getContentResolver().query(
                            MediaStore.Files.getContentUri("external"),
                            new String[]{MediaStore.MediaColumns.DATA},
                            MediaStore.MediaColumns._ID + "=?",
                            new String[]{id.substring(4)}, null)) {
                        if (c != null && c.moveToFirst()) return c.getString(0);
                    }
                }
            }
            try (Cursor c = getContentResolver().query(uri,
                    new String[]{MediaStore.MediaColumns.DATA}, null, null, null)) {
                if (c != null && c.moveToFirst()) {
                    String p = c.getString(0);
                    if (p != null && !p.isEmpty()) return p;
                }
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    private TextView delBtn;

    // ── 输出格式：只要 SRT / 只要 LRC / 两个都要（默认两个都要）──
    private TextView fmtSrt, fmtLrc, fmtBoth;
    private String outFmt = "both";

    // ── 字幕封装：off = 只出字幕文件；on = 另存一个内嵌字幕的 mkv ──
    private TextView softOff, softOn, softHint;
    private String softSub = "off";
    // ── 识别模式：融合（默认）/ zipformer 单模 / SenseVoice 单模 ──
    private TextView asrFusion, asrZip, asrSv, asrHintView;
    private String asrMode = "fusion";
    // ── 字幕语言：中文翻译（默认）/ 仅日文（不翻译，跳过 2 GB 翻译模型）──
    private TextView langZh, langJa;
    private String outLang = "zh";
    // 识别进度基准（段数 + 时间戳），用来算「段/分」
    // 识别速度用 60 秒滑动窗口 [毫秒, 已完成段数]。
    // 原来是「相邻两次采样做差」：sherpa 每批退出重启那 1~2 秒采不到进度，
    // 恢复后 4 段 / 2 秒 → 算出 120 段/分、39× 实时，比真实（约 22 段/分、7×）虚高 5 倍。
    private final java.util.ArrayDeque<long[]> asrSamples = new java.util.ArrayDeque<>();
    // 批次之间 sherpa 会退出再重启（MEM 保护），那 1~2 秒读不到进度。
    // 段速/倍速要「一直显示」，所以把最后一次算出的值和进度记下来接着用。
    private volatile double lastSpm = 0;
    private volatile int lastAsrDone = -1, lastAsrTotal = 0;
    private long mtStartMs = 0;   // 翻译阶段计时起点（算倍速用）
    // 「共 N 段 / 第 k 段」：识别读 bin/_asr_progress，翻译读 bin/_mt_progress，
    // 都是 run.sh / native 层每完成一段覆盖写的 "done total"。segPhase 决定进度条走哪一段区间。
    private int segDone = -1, segTotal = -1;
    private String segPhase = null;
    private long asrPrevMs = 0;
    private final java.util.LinkedHashSet<String> selSet = new java.util.LinkedHashSet<>();

    private static String srtToLrcName(String srtName) {
        if (srtName.endsWith(".srt")) {
            return srtName.substring(0, srtName.length() - 4) + ".lrc";
        }
        return srtName + ".lrc";
    }

    /** 两个字一排的按钮槽位（语言选择用）。 */
    private LinearLayout.LayoutParams half() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, (int) dp(44), 1f);
        lp.leftMargin = (int) dp(3);
        lp.rightMargin = (int) dp(3);
        return lp;
    }

    private TextView langPill(String text, final String key) {
        TextView b = narrowPill(text);
        b.setTag(key);
        b.setOnClickListener(v -> {
            if (!key.equals(outLang)) {
                outLang = key;
                getSharedPreferences("nihon", MODE_PRIVATE).edit()
                        .putString("lang", outLang).apply();
                applyLang();
            }
        });
        return b;
    }


    /** 识别模式按钮：融合（默认）/ zipformer 单模 / SenseVoice 单模。 */
    private TextView asrPill(String text, final String key) {
        TextView b = narrowPill(text);
        b.setTag(key);
        b.setOnClickListener(v -> {
            if (busy) {
                // 任务跑起来后模式已经传给 run.sh 了，这时候改只会让人以为生效了
                toast("任务进行中，改不了识别模式；等它结束或先点「停止」");
                return;
            }
            if (!key.equals(asrMode)) {
                asrMode = key;
                getSharedPreferences("nihon", MODE_PRIVATE).edit()
                        .putString("asr", asrMode).apply();
                applyAsr();
            }
        });
        return b;
    }

    /** 选中态 + 说明文字（把两个模型的差别讲清楚，别让人猜）。 */
    private void applyAsr() {
        // 处理中（包括暂停）锁住：模式在启动时就交给 run.sh 了，中途改不会生效
        final boolean locked = busy;
        for (TextView b : new TextView[]{asrFusion, asrZip, asrSv}) {
            if (b == null) continue;
            boolean on = asrMode.equals(b.getTag());
            b.setTextColor(on ? 0xFFFFFFFF : INK2);
            b.setBackground(on ? round(ACCENT_DEEP, 22, 0, 0) : round(SUNK, 22, CARD_LINE, 1));
            b.setEnabled(!locked);
            b.setAlpha(locked ? 0.45f : 1f);
        }
        if (asrHintView == null) return;
        if ("zip".equals(asrMode)) {
            asrHintView.setText("只用 zipformer：认得更准，但安静段/纯音效段可能漏句");
        } else if ("sv".equals(asrMode)) {
            asrHintView.setText("只用 SenseVoice：快、覆盖广，长难句和同音字容易错");
        } else {
            asrHintView.setText("两个模型都用：覆盖广、更准，速度略慢（默认）");
        }
    }
    private void applyLang() {
        for (TextView b : new TextView[]{langZh, langJa}) {
            if (b == null) {
                continue;
            }
            boolean on = outLang.equals(b.getTag());
            b.setTextColor(on ? 0xFFFFFFFF : INK2);
            b.setBackground(on ? round(ACCENT_DEEP, 22, 0, 0) : round(SUNK, 22, CARD_LINE, 1));
        }
    }

    /** 输出格式按钮：选中时砖红底白字，未选中是浅底描边。 */
    private TextView fmtPill(String text, final String key) {
        TextView b = narrowPill(text);
        b.setTag(key);
        b.setOnClickListener(v -> {
            if (!key.equals(outFmt)) {
                outFmt = key;
                getSharedPreferences("nihon", MODE_PRIVATE).edit().putString("fmt", outFmt).apply();
                applyFmt();
            }
        });
        return b;
    }

    private void applyFmt() {
        for (TextView b : new TextView[]{fmtSrt, fmtLrc, fmtBoth}) {
            if (b == null) {
                continue;
            }
            boolean on = outFmt.equals(b.getTag());
            b.setTextColor(on ? 0xFFFFFFFF : INK2);
            b.setBackground(on ? round(ACCENT_DEEP, 22, 0, 0) : round(SUNK, 22, CARD_LINE, 1));
        }
    }

    /** 字幕封装按钮：开软字幕前先弹一次说明，讲清会在原片旁多存一个文件。 */
    private TextView softPill(String text, final String key) {
        TextView b = narrowPill(text);
        b.setTag(key);
        b.setOnClickListener(v -> {
            if (key.equals(softSub)) {
                return;
            }
            if ("on".equals(key)) {
                showSoftSubTip();
            } else {
                softSub = key;
                getSharedPreferences("nihon", MODE_PRIVATE).edit().putString("soft", softSub).apply();
                applySoft();
            }
        });
        return b;
    }

    private void applySoft() {
        for (TextView b : new TextView[]{softOff, softOn}) {
            if (b == null) {
                continue;
            }
            boolean on = softSub.equals(b.getTag());
            b.setTextColor(on ? 0xFFFFFFFF : INK2);
            b.setBackground(on ? round(ACCENT_DEEP, 22, 0, 0) : round(SUNK, 22, CARD_LINE, 1));
        }
        if (softHint != null) {
            softHint.setText("on".equals(softSub)
                    ? "会在原片旁另存一个 .mkv，字幕嵌在里面"
                    : "字幕单独成文件，播放器里要自己选字幕");
        }
    }

    /** 第一次开软字幕时把代价讲清楚（用户要求：必须提醒会多一个文件）。 */
    private void showSoftSubTip() {
        new android.app.AlertDialog.Builder(this)
                .setTitle("开启软字幕")
                .setMessage("不再单独留字幕文件，改为在原片旁另存一个 .mkv，"
                        + "字幕嵌在视频里，播放器里可以开关。\n\n"
                        + "· 不重新编码，体积和原片差不多\n"
                        + "· 做完会问你要不要删掉原文件")
                .setPositiveButton("开启", (d, w) -> {
                    softSub = "on";
                    getSharedPreferences("nihon", MODE_PRIVATE).edit().putString("soft", softSub).apply();
                    applySoft();
                })
                .setNegativeButton("算了", null)
                .show();
    }

    private LinearLayout.LayoutParams third() {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0,
                (int) dp(44), 1f);
        lp.leftMargin = (int) dp(3);
        lp.rightMargin = (int) dp(3);
        return lp;
    }

    /** 三等分按钮：强制单行 + 收窄内边距，否则 4 个字会被挤成两行 */
    private TextView narrowPill(String text) {
        TextView b = pill(text, false, 44);
        b.setSingleLine(true);
        b.setEllipsize(null);
        b.setPadding((int) dp(8), 0, (int) dp(8), 0);
        b.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP, 13.5f);
        return b;
    }

    /** 没有选中时「删除选中」灰掉，有选中时显示数量并变红 */
    private void applySelState() {
        if (delBtn == null) {
            return;
        }
        int n = selSet.size();
        delBtn.setEnabled(n > 0);
        delBtn.setAlpha(n > 0 ? 1f : 0.4f);
        delBtn.setTextColor(n > 0 ? WARN : INK3);
    }

    private void refreshFiles() {
        saveQueueState();   // 队列/状态一变就落盘：App 意外退出后靠它续跑
        fileBox.removeAllViews();
        int animIndex = 0;
        if (picked.isEmpty()) {
            fileBox.addView(label("（列表为空，点下面的按钮添加视频或音频）", INK3, 12.5f));
            selSet.clear();
            applySelState();
            return;
        }
        for (int k = 0; k < picked.size(); k++) {
            final String p = picked.get(k);
            LinearLayout row = new LinearLayout(this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setPadding((int) dp(2), (int) dp(7), (int) dp(2), (int) dp(7));

            TextView num = label((k + 1) + ".", ACCENT, 12f);
            LinearLayout.LayoutParams nlp = new LinearLayout.LayoutParams(
                    (int) dp(20), ViewGroup.LayoutParams.WRAP_CONTENT);
            row.addView(num, nlp);

            TextView name = label(new File(p).getName(), INK2, 12.5f);
            name.setSingleLine(true);
            name.setEllipsize(android.text.TextUtils.TruncateAt.MIDDLE);
            row.addView(name, new LinearLayout.LayoutParams(0,
                    ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            String st = stateOf(p);
            int col = "完成".equals(st) ? OK_GREEN
                    : ("失败".equals(st) ? WARN
                    : ("处理中".equals(st) ? ACCENT : INK3));
            TextView state = label(st, col, 11f);
            LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            slp.leftMargin = (int) dp(4);
            row.addView(state, slp);

            final String path = p;
            final LinearLayout r = row;
            final int markColor = ACCENT;
            row.setOnClickListener(v -> {
                if (selSet.contains(path)) {
                    selSet.remove(path);
                } else {
                    selSet.add(path);
                }
                boolean on = selSet.contains(path);
                r.setBackground(on ? round(ACCENT_SOFT, 6, 0, 0) : null);
                r.setPadding((int) dp(2), (int) dp(7), (int) dp(2), (int) dp(7));
                applySelState();
            });
            row.setAlpha(0f);
            row.animate().alpha(1f).setStartDelay(30L * k).setDuration(200).start();
            fileBox.addView(row);
        }
        applySelState();
    }

    // ── 目录 ─────────────────────────────────────────────
    private File appDir() {
        return new File(getFilesDir(), "asmr");
    }

    /** 输入目录：放 App 的外部目录，用户可以用数据线直接往里拷，不用 adb。 */
    private File importDir() {
        File d = new File(getExternalFilesDir(null), "input");
        d.mkdirs();
        return d;
    }

    private File binDir() {
        File d = new File(appDir(), "bin");
        d.mkdirs();
        return d;
    }

    /**
     * 清掉上一轮处理留在 bin/ 里的临时文件（提取的音频、分段、进度、错误输出）。
     * run.sh 正常结束会自己清，这里兜底两种情况：
     *   ① 被 kill -9 / App 被系统杀掉，trap 没机会跑；
     *   ② 老版本跑完留了一堆。
     * 启动、每次开跑前、每轮结束后各扫一遍 —— 不让 App 私有目录变成垃圾场。
     */
    private void cleanWorkFiles() {
        File bin = new File(appDir(), "bin");   // 不走 binDir()，免得顺手 mkdirs
        if (!bin.isDirectory()) return;
        File[] items = bin.listFiles();
        if (items == null) return;
        long freed = 0;
        int n = 0;
        for (File x : items) {
            String name = x.getName();
            boolean junk = name.startsWith("_asmr_")
                    || name.startsWith("_seg_")
                    || name.equals("_asr_progress")
                    || name.equals("_mt_progress")
                    || name.equals("_sv_progress")
                    || name.equals("_mt_preview")
                    || name.equals("asr.err")
                    // 老版本留下的「已解压」标记，只有当前这版有用
                    || (name.startsWith(".assets_")
                        && !name.equals(".assets_" + ASSETS_VER));
            if (!junk) continue;
            freed += sizeOf(x);
            deleteTree(x);
            n++;
        }
        // llama-server 是刻意驻留复用的，只有它真的不在了，pid 文件才算垃圾
        File pid = new File(bin, "_llama.pid");
        if (pid.isFile() && liveLlamaPid() <= 0) {
            pid.delete();
            n++;
        }
        if (n > 0) {
            appendLog("[清理] 清掉 " + n + " 个上次残留的临时文件，释放 "
                    + (freed / 1048576L) + " MB");
        }
    }

    private static long sizeOf(File f) {
        if (f.isFile()) return f.length();
        File[] kids = f.listFiles();
        if (kids == null) return 0;
        long s = 0;
        for (File k : kids) s += sizeOf(k);
        return s;
    }

    private static void deleteTree(File f) {
        if (f.isDirectory()) {
            File[] kids = f.listFiles();
            if (kids != null) for (File k : kids) deleteTree(k);
        }
        f.delete();
    }

    /** 字幕输出目录：app 的外部 files 目录，用户可用文件管理器访问。 */
    private File outputDir() {
        File d = new File(getExternalFilesDir(null), "output");
        d.mkdirs();
        return d;
    }

    /**
     * 模型目录：私有目录 /data/user/0/com.nihonsubs/files/models。
     * 为什么不用 /sdcard/Android/data/.../models：
     *   1) 那里是 FUSE 用户态文件系统，llama 用 mmap 加载 1.1 GB gguf 时缺页
     *      要绕一大圈，实测把 loadavg 顶到 100+，系统进程先 ANR（2026-11-11 事故）；
     *   2) 外部目录会被系统扫描、被清理软件删，私有目录不会。
     * 空间够（用户机 /data 剩 93 GB），所以主体模型搬进来。
     */
    private File modelDir() {
        File d = new File(getFilesDir(), "models");
        if (!d.isDirectory()) d.mkdirs();
        return d;
    }

    /** 老版本的模型目录（外部存储），只用于一次性搬迁。 */
    private File legacyModelDir() {
        File ext = getExternalFilesDir(null);
        return ext == null ? null : new File(ext, "models");
    }

    /**
     * 一次性搬迁：把老版本放在 /sdcard 的模型文件移到私有目录，移完删源文件腾空间。
     * 在后台线程跑，失败也不影响旧目录继续用（run.sh 两边都会找）。
     */
    private void migrateModelsIfNeeded() {
        final File dst = modelDir();
        final File old = legacyModelDir();
        if (old == null || !old.isDirectory()) return;
        final String[] names = {"hy.gguf", "model.int8.onnx",
                "zipformer-ja/encoder.int8.onnx", "zipformer-ja/decoder.onnx",
                "zipformer-ja/joiner.onnx", "zipformer-ja/tokens.txt"};
        java.util.List<String> todo = new java.util.ArrayList<>();
        for (String n : names) {
            if (new File(old, n).isFile() && !new File(dst, n).isFile()) todo.add(n);
        }
        if (todo.isEmpty()) return;
        migratingModels = true;
        try {
            ui.post(() -> appendLog("[模型] 正在把模型搬到应用私有目录（一次性，之后加载更快）…"));
            for (String n : todo) {
                File s = new File(old, n);
                File t = new File(dst, n);
                File parent = t.getParentFile();
                if (parent != null && !parent.isDirectory()) parent.mkdirs();
                copyFile(s, t);
                if (t.length() == s.length()) s.delete();
            }
            new File(old, "zipformer-ja").delete();
            old.delete();
            ui.post(() -> {
                appendLog("[模型] 搬迁完成：" + dst.getAbsolutePath());
                refreshModelRow();
            });
        } catch (Exception e) {
            ui.post(() -> appendLog("[模型] 搬迁失败（旧目录仍可用）：" + e));
        } finally {
            migratingModels = false;
        }
    }

    private void copyFile(File src, File dst) throws java.io.IOException {
        try (InputStream in = new FileInputStream(src);
             OutputStream out = new FileOutputStream(dst)) {
            byte[] buf = new byte[1 << 20];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        }
    }

    /** 把 assets 里的可执行文件与运行库解压到私有目录（只需一次）。 */
    private void extractAssets() throws Exception {
        File bin = binDir();
        // 可执行文件都在 nativeLibraryDir（lib/arm64-v8a），这里只解压数据文件
        String[] names = {"run.sh", "silero_vad.onnx", "tokens.txt"};
        for (String n : names) {
            File out = new File(bin, n);
            try (InputStream in = getAssets().open(n);
                 OutputStream os = new FileOutputStream(out)) {
                byte[] buf = new byte[1 << 16];
                int r;
                while ((r = in.read(buf)) > 0) os.write(buf, 0, r);
            }
            out.setReadable(true, false);
            out.setExecutable(true, false);
        }
        new FileOutputStream(new File(bin, ".assets_" + ASSETS_VER)).close();
    }

    /** assets 里的 run.sh 有改动时 +1，老设备靠这个戳记重新解压。 */
    private static final String ASSETS_VER = "v18";

    private boolean binReady() {
        return new File(binDir(), ".assets_" + ASSETS_VER).isFile()
                && new File(binDir(), "run.sh").isFile()
                && new File(binDir(), "silero_vad.onnx").isFile()
                && new File(binDir(), "tokens.txt").isFile();
    }

    // ── 执行 ─────────────────────────────────────────────
    private void start() {
        if (busy) return;
        if (picked.isEmpty()) {
            appendLog("[提示] 先添加要处理的视频或音频");
            return;
        }
        if (migratingModels) {
            appendLog("[提示] 正在搬迁模型文件，几秒后就能开始，请稍等再点一次。");
            return;
        }
        File md = modelDir();
        boolean hasModel = new File(md, "model.int8.onnx").isFile() && new File(md, "hy.gguf").isFile();
        if (!hasModel) {
            appendLog("[缺少模型] 首次使用需要模型（共约 1.35 GB）。");
            appendLog("[缺少模型] 推荐：选下载好的 asmr-models.zip，程序会自动解压；");
            appendLog("            也可以依次单独选 model.int8.onnx（约 230 MB）和 hy.gguf（约 1.1 GB）。");
            appendLog("[缺少模型] 目标目录：" + md.getAbsolutePath());
            setStage("当前阶段　等待模型");
            promptModelDownload();
            return;
        }
        busy = true;
        stopBtn.setEnabled(true);
        stopBtn.setAlpha(1f);
        startBtn.setEnabled(false);
        startBtn.setAlpha(0.4f);
        stopping = false;
        curIdx = 0;
        fileProgress = 0f;
        lastSpm = 0;
        lastAsrDone = -1;
        mtStartMs = 0;
        lastAsrTotal = 0;
        asrSamples.clear();
        if (skipDoneFiles) {
            // 续跑上次队列：已完成的保留，别重置成「待处理」
            for (String p : picked) {
                if (!fileState.containsKey(p)) fileState.put(p, "待处理");
            }
        } else {
            fileState.clear();
            for (String p : picked) fileState.put(p, "待处理");
        }
        if (pauseBtn != null) {
            pauseBtn.setText("暂停");
            pauseBtn.setEnabled(true);
            pauseBtn.setAlpha(1f);
        }
        paused = false;
        applyAsr();          // 跑起来就锁住识别模式
        setQueueRunning(true);
        refreshFiles();
        progress = 0f;         // 上一轮的 100% 必须先归零，setProgress 只会往上走
        applyProgress();
        setProgress(0f, true);
        setStage("当前阶段　准备中");
        logView.setText("");
        if (previewView != null) previewView.setText("正在准备…马上开始出字幕");
        // 开新任务前再兜一次：哪怕上一轮是被 kill -9 干掉的，也不把残留带进这一轮
        cleanWorkFiles();
        deleteSoftList();
        appendLog("[模式] 识别：" + ("zip".equals(asrMode) ? "zipformer 单模"
                : ("sv".equals(asrMode) ? "SenseVoice 单模" : "融合（zipformer + SenseVoice）"))
                + "；字幕：" + ("ja".equals(outLang) ? "仅日文" : "中文翻译")
                + "；格式：" + ("srt".equals(outFmt) ? "SRT" : ("lrc".equals(outFmt) ? "LRC" : "SRT + LRC"))
                + "；封装：" + ("on".equals(softSub) ? "软字幕 mkv" : "外挂字幕"));

        Thread t = new Thread(this::runPipeline, "pipeline");
        t.start();
    }

    // ── 模型导入（SAF 文件选择器）────────────────────────
    private void startModelImport() {
        dismissModelDialog();
        appendLog("[模型] 请选择 asmr-models.zip（推荐，一次装齐）；也可单独选 model.int8.onnx 或 hy.gguf");
        pickModelFile();
    }

    private void pickModelFile() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        try {
            startActivityForResult(i, REQ_MODEL);
        } catch (Exception e) {
            appendLog("[模型] 打不开文件选择器：" + e);
        }
    }

    /** 从所选 Uri 查出文件名（有的选择器不返回路径，只能查 OpenableColumns）。 */
    private String displayName(Uri uri) {
        try (Cursor c = getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (i >= 0) return c.getString(i);
            }
        } catch (Exception ignored) {
        }
        return uri.getLastPathSegment();
    }

    /** 解压 asmr-models.zip：取 model.int8.onnx、hy.gguf 与 zipformer-ja/ 的 4 个文件（短名）。 */
    private void unzipModels(final Uri uri) {
        final File md = modelDir();
        appendLog("[模型] 正在解压模型包 …");
        setStage("当前阶段　导入模型");
        int got = 0;
        try (InputStream raw = getContentResolver().openInputStream(uri);
             ZipInputStream zin = new ZipInputStream(raw)) {
            if (raw == null) throw new java.io.IOException("无法打开所选文件");
            ZipEntry e;
            byte[] buf = new byte[1 << 20];
            while ((e = zin.getNextEntry()) != null) {
                if (e.isDirectory()) continue;
                String name = e.getName().replace('\\', '/');
                String base = new File(name).getName();
                boolean core = "model.int8.onnx".equals(base) || "hy.gguf".equals(base);
                boolean zipf = "zipformer-ja/encoder.int8.onnx".equals(name)
                        || "zipformer-ja/decoder.onnx".equals(name)
                        || "zipformer-ja/joiner.onnx".equals(name)
                        || "zipformer-ja/tokens.txt".equals(name);
                if (!core && !zipf) continue;
                File dst;
                if (core) {
                    dst = new File(md, base);
                } else {
                    File zdir = new File(md, "zipformer-ja");
                    if (!zdir.isDirectory() && !zdir.mkdirs() && !zdir.isDirectory())
                        throw new java.io.IOException("无法创建 zipformer-ja 目录");
                    dst = new File(zdir, base);
                }
                appendLog("[模型] 解压 " + (zipf ? "zipformer-ja/" : "") + base + " …");
                long total = 0, last = 0;
                try (OutputStream out = new FileOutputStream(dst)) {
                    int n;
                    while ((n = zin.read(buf)) > 0) {
                        out.write(buf, 0, n);
                        total += n;
                        if (total - last >= 64L * 1024 * 1024) {
                            last = total;
                            final long mb = total / (1024 * 1024);
                            ui.post(() -> appendLog("        已写入 " + mb + " MB"));
                        }
                    }
                }
                appendLog("[模型] 完成：" + base + "，" + (total / (1024 * 1024)) + " MB");
                ui.post(this::refreshModelRow);
                got++;
            }
        } catch (Exception ex) {
            appendLog("[模型] 解压失败：" + ex);
            ui.post(() -> setStage("当前阶段　等待模型"));
            return;
        }
        final int n = got;
        ui.post(() -> {
            dismissModelDialog();   // 导完就把引导弹窗收掉，不让它挂在屏幕上
            boolean ok = new File(md, "model.int8.onnx").isFile() && new File(md, "hy.gguf").isFile();
            if (ok) {
                appendLog("[模型] 模型包导入完成（" + n + " 个文件），点「开始处理」即可");
                setStage("当前阶段　就绪");
            } else {
                appendLog("[模型] 压缩包里没找到 model.int8.onnx / hy.gguf，请确认选的是 asmr-models.zip");
                setStage("当前阶段　等待模型");
            }
        });
    }

    /**
     * 导入单个模型文件：按**用户实际选中的文件名**决定存到哪儿，不再依赖先选谁。
     * 这样顺序随便（先 hy.gguf 也行），压缩包里的文件也能一个个单独导入。
     */
    private void copyModel(final Uri uri) {
        String display = displayName(uri);
        String lower = display == null ? "" : display.toLowerCase(java.util.Locale.ROOT);
        if (lower.endsWith(".zip")) {
            unzipModels(uri);
            return;
        }
        final String target;      // 落盘用的文件名
        final boolean zipf;       // 是否放进 zipformer-ja/
        // 兼容两种命名：短名 encoder.int8.onnx，以及原始训练名 encoder-epoch-99-avg-1.int8.onnx
        if (lower.endsWith("model.int8.onnx")) {
            target = "model.int8.onnx"; zipf = false;
        } else if (lower.endsWith(".gguf")) {
            target = "hy.gguf"; zipf = false;
        } else if (lower.endsWith(".onnx") && lower.contains("encoder")) {
            target = "encoder.int8.onnx"; zipf = true;
        } else if (lower.endsWith(".onnx") && lower.contains("decoder")) {
            target = "decoder.onnx"; zipf = true;
        } else if (lower.endsWith(".onnx") && lower.contains("joiner")) {
            target = "joiner.onnx"; zipf = true;
        } else if (lower.endsWith("tokens.txt")) {
            target = "tokens.txt"; zipf = true;
        } else {
            appendLog("[模型] 不认识这个文件：" + display);
            appendLog("[模型] 请选 asmr-models.zip，或 model.int8.onnx / hy.gguf");
            return;
        }
        final File dir = zipf ? new File(modelDir(), "zipformer-ja") : modelDir();
        if (zipf && !dir.isDirectory() && !dir.mkdirs() && !dir.isDirectory()) {
            appendLog("[模型] 无法创建 zipformer-ja 目录");
            return;
        }
        final File dst = new File(dir, target);
        final String shown = (zipf ? "zipformer-ja/" : "") + target;
        appendLog("[模型] 正在导入 " + shown + " …");
        ui.post(() -> setStage("当前阶段　导入模型"));
        long total = 0;
        try (InputStream in = getContentResolver().openInputStream(uri);
             OutputStream out = new FileOutputStream(dst)) {
            if (in == null) throw new java.io.IOException("无法打开所选文件");
            byte[] buf = new byte[1 << 20];
            int n;
            long last = 0;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
                total += n;
                if (total - last >= 64L * 1024 * 1024) {
                    last = total;
                    final long mb = total / (1024 * 1024);
                    ui.post(() -> appendLog("        已写入 " + mb + " MB"));
                }
            }
        } catch (Exception e) {
            appendLog("[模型] 导入失败：" + e + "（旧文件已删除，可重试）");
            dst.delete();
            ui.post(() -> setStage("当前阶段　等待模型"));
            return;
        }
        final long mb = total / (1024 * 1024);
        appendLog("[模型] 完成：" + shown + "，" + mb + " MB");
        // 体积校验：防止把 hy.gguf 当成 model.int8.onnx 存进去（以前会静默写错）
        if ("model.int8.onnx".equals(target) && mb > 500) {
            appendLog("[警告] model.int8.onnx 正常约 230 MB，这个文件 " + mb + " MB —— 是不是选成 hy.gguf 了？");
        }
        if ("hy.gguf".equals(target) && mb < 300) {
            appendLog("[警告] hy.gguf 正常约 1080 MB，这个文件只有 " + mb + " MB，可能选错了");
        }
        ui.post(this::refreshModelRow);
        final File md2 = modelDir();
        ui.post(() -> {
            dismissModelDialog();
            boolean onnx = new File(md2, "model.int8.onnx").isFile();
            boolean gguf = new File(md2, "hy.gguf").isFile();
            if (onnx && gguf) {
                appendLog("[模型] 两个模型都已就位，点「开始处理」即可");
                setStage("当前阶段　就绪");
            } else {
                appendLog("[模型] 还缺 " + (!onnx ? "model.int8.onnx" : "hy.gguf") + "，点「导入模型」继续选下一个");
                setStage("当前阶段　等待模型");
            }
        });
    }

    // ── 暂停 / 继续 ─────────────────────────────────────
    /**
     * 冻结 / 解冻整条流水线。
     * 不用「停了重来」那么伤：run.sh 及其拉起的 sherpa / llama 进程被 SIGSTOP 挂起，
     * 内存里的进度（已识别的分段、已翻译的字幕）都还在，解冻后原地接着跑。
     */
    private void togglePause() {
        if (!busy) return;
        if (!paused) {
            paused = true;
            pauseBtn.setText("继续");
            sendSignal(true);
            appendLog("[暂停] 处理进程已冻结，点「继续」原地接着跑，进度不会丢");
            setStage("当前阶段　已暂停");
        } else {
            paused = false;
            pauseBtn.setText("暂停");
            sendSignal(false);
            appendLog("[继续] 已恢复处理");
            setStage("当前阶段　处理中");
        }
    }

    /** 对流水线里的所有进程发 SIGSTOP / SIGCONT（同 uid 才有权限，够用）。 */
    private void sendSignal(final boolean freeze) {
        final String sig = freeze ? "-STOP" : "-CONT";
        new Thread(() -> {
            java.util.Set<Long> pids = new java.util.LinkedHashSet<>();
            long sh = liveShellPid();
            if (sh > 0) pids.add(sh);
            // run.sh 里的重活都在这些 native 进程里，必须一起冻、一起解
            for (String name : new String[]{"libllamaserver.so", "libsherpaoffline.so",
                    "libvadasr.so", "libffmpeg.so"}) {
                long p = findPidByCmdline(name);
                if (p > 0) pids.add(p);
            }
            int sent = 0;
            for (long p : pids) {
                try {
                    Process k = new ProcessBuilder("/system/bin/kill", sig, String.valueOf(p))
                            .redirectErrorStream(true).start();
                    k.waitFor();
                    sent++;
                } catch (Exception ignored) {
                }
            }
            final int n = sent;
            ui.post(() -> appendLog("[暂停] " + (freeze ? "冻结" : "恢复") + "了 " + n + " 个处理进程"));
        }, "pause-signal").start();
    }

    /** 当前 run.sh 进程的 pid；拿不到返回 -1。 */
    private long liveShellPid() {
        if (running == null) return -1;
        try {
            java.lang.reflect.Method m = Process.class.getMethod("pid");
            Object v = m.invoke(running);
            return v instanceof Long ? (Long) v : -1;
        } catch (Exception e) {
            return -1;
        }
    }

    // ── 队列持久化（App 被系统杀掉后下次能续跑）────────────
    /** 把队列 + 每个文件的状态写盘。「处理中」按「待处理」存，免得崩溃后卡在中间态。 */
    private void saveQueueState() {
        StringBuilder sb = new StringBuilder();
        for (String p : picked) {
            String st = stateOf(p);
            if ("处理中".equals(st) || "已停止".equals(st)) st = "待处理";
            sb.append(st).append('\t').append(p).append('\n');
        }
        getSharedPreferences(QPREF, MODE_PRIVATE).edit()
                .putString("files", sb.toString()).apply();
    }

    /** 单独标记「队列进行中」；只有 start / finishRun 改它，避免刷新列表时误清。 */
    private void setQueueRunning(boolean r) {
        getSharedPreferences(QPREF, MODE_PRIVATE).edit().putBoolean("running", r).apply();
    }

    /** 上次队列没跑完 → 弹窗问要不要接着跑，已完成的自动跳过。 */
    private void promptResumeQueue() {
        if (busy) return;
        if (!resumeWasRunning) return;
        resumeWasRunning = false;      // 只提示一次
        String raw = resumeFilesRaw;
        if (raw == null || raw.isEmpty()) return;
        final java.util.List<String[]> rows = new java.util.ArrayList<>();
        int doneN = 0, leftN = 0;
        for (String line : raw.split("\n")) {
            if (line.isEmpty()) continue;
            int t = line.indexOf('\t');
            if (t <= 0) continue;
            String st = line.substring(0, t);
            String path = line.substring(t + 1);
            if (!new File(path).exists()) continue;   // 文件被删了就别提
            rows.add(new String[]{path, "完成".equals(st) ? "完成" : "待处理"});
            if ("完成".equals(st)) doneN++; else leftN++;
        }
        if (rows.isEmpty() || leftN == 0) {
            setQueueRunning(false);
            return;
        }
        final int total = rows.size(), done = doneN, left = leftN;
        new android.app.AlertDialog.Builder(this)
                .setTitle("继续上次队列")
                .setMessage("上次队列没跑完：共 " + total + " 个文件，已完成 " + done + " 个，还有 "
                        + left + " 个没处理。\n\n点「继续」接着跑，已完成的会自动跳过。")
                .setPositiveButton("继续", (d, w) -> {
                    picked.clear();
                    fileState.clear();
                    for (String[] r : rows) {
                        picked.add(r[0]);
                        fileState.put(r[0], r[1]);
                    }
                    skipDoneFiles = true;
                    refreshFiles();
                    refreshQueue();
                    start();
                })
                .setNegativeButton("忽略", (d, w) -> {
                    setQueueRunning(false);
                    appendLog("[队列] 已忽略上次没跑完的任务");
                })
                .setCancelable(true)
                .show();
    }

    private void stop() {
        stopping = true;
        if (paused) {           // 冻结状态下也能杀，但先解冻更干净
            paused = false;
            if (pauseBtn != null) pauseBtn.setText("暂停");
            sendSignal(false);
        }
        if (running != null) {
            try {
                running.destroy();
            } catch (Exception ignored) {
            }
            appendLog("[停止] 已请求结束当前进程");
        }
        // 让 runPipeline 的循环自己收尾（这样它不会再跑下一个文件）；
        // 万一线程已经死在别处，3 秒后兜底复位界面。
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(3000);
            } catch (InterruptedException ignored) {
            }
            ui.post(() -> {
                if (busy) finishRun();
            });
        }, "stop-guard");
        t.start();
    }

    private TextView elapsedView;
    private long runStartMs = 0L;
    private final android.os.Handler timeHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable timeTick = new Runnable() {
        @Override
        public void run() {
            if (runStartMs <= 0L) {
                return;
            }
            long sec = (System.currentTimeMillis() - runStartMs) / 1000L;
            elapsedView.setText("已用 " + fmtDuration(sec));
            // 每 500ms 刷新一次，秒数跳动更跟手
            timeHandler.postDelayed(this, 500);
        }
    };

    private void startTimer() {
        runStartMs = System.currentTimeMillis();
        elapsedView.setText("已用 00:00");
        timeHandler.removeCallbacks(timeTick);
        timeHandler.post(timeTick);
    }

    private void stopTimer() {
        timeHandler.removeCallbacks(timeTick);
        if (runStartMs > 0L) {
            long sec = (System.currentTimeMillis() - runStartMs) / 1000L;
            elapsedView.setText("用时 " + fmtDuration(sec));
        }
        runStartMs = 0L;
    }

    private static String fmtDuration(long sec) {
        if (sec < 60) {
            return String.format(java.util.Locale.ROOT, "00:%02d", sec);
        }
        long h = sec / 3600, m = (sec % 3600) / 60, s = sec % 60;
        if (h > 0) {
            return String.format(java.util.Locale.ROOT, "%d:%02d:%02d", h, m, s);
        }
        return String.format(java.util.Locale.ROOT, "%02d:%02d", m, s);
    }

    private void runPipeline() {
        startTimer();
        startStats();
        startKeepAlive();
        try {
            File bin = binDir();
            if (!binReady()) {
                appendLog("[准备] 首次启动，正在解压运行文件（约 59 MB）…");
                extractAssets();
                appendLog("[准备] 解压完成");
            }
            File script = new File(bin, "run.sh");
            if (!script.exists()) {
                appendLog("[错误] 缺少运行脚本：" + script);
                ui.post(this::finishRun);
                return;
            }
            final java.util.List<String> snapshot = new ArrayList<>(picked);
            final java.util.Set<String> usedOut = new java.util.HashSet<>();
            int idx = 0;
            for (String file : snapshot) {
                if (stopping) break;
                if (skipDoneFiles && "完成".equals(stateOf(file))) {
                    appendLog("[跳过] " + new File(file).getName() + " 上次已经完成");
                    continue;
                }
                final int fi = idx;
                curIdx = fi;
                curFile = file;
                fileProgress = 0f;
                fileState.put(file, "处理中");
                ui.post(() -> {
                    refreshFiles();
                    refreshQueue();
                    setStage("当前阶段　第 " + (fi + 1) + "/" + snapshot.size() + " 个");
                });
                appendLog("=== 开始处理：" + new File(file).getName() + " ===");
                String stem = new File(file).getName();
                int dot = stem.lastIndexOf('.');
                if (dot > 0) stem = stem.substring(0, dot);
                // 字幕默认写到原片所在文件夹（需要「所有文件访问」授权）；
                // 没授权或原目录不可写时退回 App 自己的 output 目录。
                File srcDir = new File(file).getParentFile();
                File outDir = (hasAllFiles() && srcDir != null && srcDir.canWrite())
                        ? srcDir : outputDir();
                // 仅日文时用 .ja.srt/.ja.lrc，播放器能按后缀选语言
                String langSuffix = "ja".equals(outLang) ? ".ja" : ".chs";
                File outSrt = new File(outDir, stem + langSuffix + ".srt");
                String outKey = outDir.getAbsolutePath() + "/" + outSrt.getName();
                int dup = 2;
                while (usedOut.contains(outKey) || outSrt.exists()
                        || new File(outDir, srtToLrcName(outSrt.getName())).exists()) {
                    outSrt = new File(outDir, stem + "-" + dup + langSuffix + ".srt");
                    outKey = outDir.getAbsolutePath() + "/" + outSrt.getName();
                    dup++;
                }
                usedOut.add(outKey);
                appendLog("[输出] 字幕将写入：" + outDir.getAbsolutePath());
                // 音频提取交给 run.sh 里的 ffmpeg。
                // 原方案是先用系统 MediaCodec 解码再喂给脚本（为了只带 ffmpeg 当兜底），
                // 实测 90 秒音频要 2.8 秒、91 分钟片子要 2.9 分钟，而 ffmpeg 只要约 30 秒 —— 慢 5.7 倍，
                // 用户明确否决，所以回到 ffmpeg 一把到底。
                // 第二个参数只给「输出目录」，字幕文件名由 run.sh 用输入文件的真实 basename 推导：
                // App 队列里可能存着改名前的旧路径（曾出现过视频名少一个 @、字幕名多一个 @，
                // 结果播放器按名匹配不到字幕），让脚本自己算就不会错。
                ProcessBuilder pb = new ProcessBuilder(
                        "/system/bin/sh", script.getAbsolutePath(), file, outDir.getAbsolutePath());
                pb.directory(bin);
                pb.redirectErrorStream(true);
                pb.environment().put("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir);
                pb.environment().put("ASMR_LIB", getApplicationInfo().nativeLibraryDir);
                pb.environment().put("ASMR_WORK", bin.getAbsolutePath());
                // 模型在私有目录（run.sh 优先用它，找不到才回落到 /sdcard）
                pb.environment().put("ASMR_MODEL", modelDir().getAbsolutePath());
        pb.environment().put("ASMR_FMT", outFmt);
        pb.environment().put("ASMR_SUFFIX", langSuffix);
        pb.environment().put("ASMR_SOFTSUB", "on".equals(softSub) ? "1" : "0");
        pb.environment().put("ASMR_LANG", outLang);
        pb.environment().put("ASMR_ASR", asrMode);
                running = pb.start();
                try (BufferedReader r = new BufferedReader(
                        new InputStreamReader(running.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        final String l = line;
                        ui.post(() -> onLine(l));
                    }
                }
                int code = running.waitFor();
                running = null;
                final boolean ok = (code == 0);
                final String state = stopping ? "已停止" : (ok ? "完成" : "失败");
                fileState.put(file, state);
                ui.post(() -> {
                    refreshFiles();
                    refreshQueue();
                });
                appendLog("[结束] 退出码 " + code + "（" + state + "）");
                idx++;
            }
        } catch (Exception e) {
            appendLog("[异常] " + e);
        } finally {
            ui.post(this::finishRun);
        }
    }

    // ── 字幕预览 ────────────────────────────────────────
    /**
     * 只读、只取尾部：翻译阶段看 native 每句追加的 _mt_preview；
     * 识别阶段看 sherpa 写的 JSON 行（每行一个 {"text": ...}）。
     * 两个都没有就保持原样（不把「等待中」刷来刷去）。
     */
    private void updatePreview() {
        if (previewView == null) return;
        String text = null;
        java.util.List<String> rows = new java.util.ArrayList<>();
        File mpv = new File(binDir(), "_mt_preview");
        if (mpv.isFile() && mpv.length() > 0) {
            for (String ln : readTailLines(mpv, 80)) {
                String[] p = ln.split("\t");
                if (p.length < 4) continue;
                String zh = p[3].trim();
                if (zh.isEmpty()) continue;
                rows.add(fmtClock(p[0]) + "　" + zh);
            }
        } else {
            // 识别阶段：日文原文先垫着，让用户看到确实在出东西
            File zf = newestAsrJson();
            if (zf != null && zf.length() > 0) {
                for (String ln : readTailLines(zf, 400)) {
                    String s = ln.trim();
                    if (!s.startsWith("{")) continue;
                    try {
                        String txt = new org.json.JSONObject(s).optString("text", "").trim();
                        if (!txt.isEmpty()) rows.add("· " + txt);
                    } catch (Exception ignored) {
                    }
                }
            }
        }
        if (rows.isEmpty()) return;
        int from = Math.max(0, rows.size() - 7);
        StringBuilder sb = new StringBuilder();
        for (int i = from; i < rows.size(); i++) {
            if (i > from) sb.append('\n');
            sb.append(rows.get(i));
        }
        text = sb.toString();
        final String t = text;
        ui.post(() -> {
            if (previewView == null) return;
            if (!t.equals(previewView.getText().toString())) {
                previewView.setText(t);
                // 自动滚到最新一句
                if (previewScroll != null) previewScroll.post(
                        () -> previewScroll.fullScroll(View.FOCUS_DOWN));
            }
        });
    }

    /** 翻译预览文件的最后一句结束时间（秒），用来算倍速；没有就返回 -1。 */
    private double lastPreviewEnd() {
        java.util.List<String> lines = readTailLines(new File(binDir(), "_mt_preview"), 12);
        for (int i = lines.size() - 1; i >= 0; i--) {
            String[] a = lines.get(i).split("\t");
            if (a.length >= 4) {
                try {
                    return Double.parseDouble(a[1].trim());
                } catch (Exception ignored) {
                }
            }
        }
        return -1;
    }

    /** 读文件的最后 n 行（文件都很小，直接整读再切）。 */
    private static java.util.List<String> readTailLines(File f, int n) {
        java.util.List<String> out = new java.util.ArrayList<>();
        try {
            String all = new String(java.nio.file.Files.readAllBytes(f.toPath()),
                    java.nio.charset.StandardCharsets.UTF_8);
            String[] parts = all.split("\n");
            for (int i = Math.max(0, parts.length - n); i < parts.length; i++) {
                if (!parts[i].trim().isEmpty()) out.add(parts[i]);
            }
        } catch (Exception ignored) {
        }
        return out;
    }

    /** 本次任务里最新的识别结果文件（融合用 _zip.txt，单车道用 _asmr_pid.txt）。 */
    private File newestAsrJson() {
        File[] fs = binDir().listFiles();
        if (fs == null) return null;
        File best = null;
        for (File f : fs) {
            String n = f.getName();
            if (!n.startsWith("_asmr_") || !n.endsWith(".txt")) continue;
            if (n.endsWith("_sv.txt") || n.endsWith("_dumb.txt")) continue;
            if (best == null || f.lastModified() > best.lastModified()) best = f;
        }
        return best;
    }

    private static String fmtClock(String sec) {
        double d;
        try {
            d = Double.parseDouble(sec);
        } catch (Exception e) {
            return "--:--";
        }
        int t = (int) Math.max(0, d);
        return String.format(java.util.Locale.ROOT, "%02d:%02d", t / 60, t % 60);
    }
    /** 解析子进程输出 → 阶段文字 + 进度条 + 日志。 */
    private void onLine(String line) {
        if (line.startsWith("[1/4]")) {
            setFileProgress(0.08f, true);
            setStage("当前阶段　提取音频");
        } else if (line.startsWith("[2/4]")) {
            setFileProgress(0.18f, true);
            setStage("当前阶段　语音识别");
        } else if (line.startsWith("[3/4]")) {
            setFileProgress(0.62f, true);
            setStage("当前阶段　启动翻译服务");
        } else if (line.startsWith("[4/4]")) {
            setFileProgress(0.74f, true);
            setStage("当前阶段　翻译");
        }
        if (line.contains("listening on")) {
            setFileProgress(0.36f, true);
        }
        if (line.contains("识别到")) {
            setFileProgress(0.55f, true);
        }
        if (line.contains("==== 完成")) {
            // run.sh 最后一行形如 "==== 完成：<路径>（总耗时 N 秒）===="
            int c = line.indexOf('：');
            int d = line.indexOf('（');
            if (c >= 0 && d > c) {
                final String out = line.substring(c + 1, d);
                appendLog("[输出] " + out);
            }
            setFileProgress(1f, false);
            setStage("当前阶段　完成");
            actionView.setText("当前动作　字幕已生成");
        }
        actionView.setText("当前动作　" + line);
        appendLog(line);
    }

    private void finishRun() {
        stopTimer();
        stopStats();
        stopKeepAlive();
        busy = false;
        running = null;
        paused = false;
        skipDoneFiles = false;
        setQueueRunning(false);
        if (pauseBtn != null) {
            pauseBtn.setText("暂停");
            pauseBtn.setEnabled(false);
            pauseBtn.setAlpha(0.4f);
        }
        startBtn.setEnabled(true);
        startBtn.setAlpha(1f);
        stopBtn.setEnabled(false);
        stopBtn.setAlpha(0.4f);
        applyAsr();          // 任务结束，识别模式重新可选
        int done = 0;
        for (String st : fileState.values()) {
            if ("完成".equals(st)) done++;
        }
        int total = picked.size();
        if (done > 0 && done == total) {
            setStage("当前阶段　完成");
            subtitleView.setText("本地转写 · 离线翻译 · 已完成 " + total + " 个文件");
        } else if (done > 0) {
            setStage("当前阶段　部分完成");
            subtitleView.setText("已完成 " + done + "/" + total + " 个文件");
        } else {
            setStage("当前阶段　已停止");
            subtitleView.setText("本地转写 · 离线翻译 · 手机 CPU 推理");
        }
        // 正常结束时 run.sh 自己已清干净，这里再扫一遍只是兜底，通常没有输出
        cleanWorkFiles();
        promptDeleteSources();
    }

    /** 上一轮遗留的软字幕清单，开新任务前清掉。 */
    private void deleteSoftList() {
        File f = new File(binDir(), "_soft_list");
        if (f.exists()) {
            f.delete();
        }
    }

    /** 队列跑完后问一次：带字幕的 .mkv 已经生成，要不要把原视频删掉。 */
    private void promptDeleteSources() {
        final File listFile = new File(binDir(), "_soft_list");
        if (!listFile.isFile()) {
            return;
        }
        final java.util.List<String[]> pairs = new ArrayList<>();
        try (BufferedReader r = new BufferedReader(new InputStreamReader(
                new java.io.FileInputStream(listFile), "UTF-8"))) {
            String line;
            while ((line = r.readLine()) != null) {
                int bar = line.lastIndexOf('|');
                if (bar <= 0) {
                    continue;
                }
                String src = line.substring(0, bar);
                String mkv = line.substring(bar + 1);
                // 只有 mkv 真的写出来了才建议删源，避免把片子删了字幕却没成
                if (new File(mkv).isFile()) {
                    pairs.add(new String[]{src, mkv});
                }
            }
        } catch (Exception ignored) {
        }
        listFile.delete();
        if (pairs.isEmpty()) {
            return;
        }
        final StringBuilder sb = new StringBuilder();
        sb.append("已生成 ").append(pairs.size()).append(" 个带字幕的文件（.mkv）：\n");
        for (int i = 0; i < Math.min(4, pairs.size()); i++) {
            sb.append("· ").append(new File(pairs.get(i)[1]).getName()).append("\n");
        }
        if (pairs.size() > 4) {
            sb.append("…\n");
        }
        sb.append("\n对应的原文件还留着，比较占地方。要删掉吗？\n"
                + "字幕已经嵌在 .mkv 里，删了照样能看。");
        ui.post(() -> new android.app.AlertDialog.Builder(this)
                .setTitle("删掉原文件？")
                .setMessage(sb.toString())
                .setPositiveButton("删掉原文件", (d, w) -> {
                    int n = 0;
                    for (String[] pr : pairs) {
                        if (new File(pr[0]).delete()) {
                            n++;
                        }
                    }
                    appendLog("[清理] 删掉了 " + n + " 个原文件，只保留带字幕的 .mkv");
                    toast("已删除 " + n + " 个原文件");
                })
                .setNegativeButton("都留着", null)
                .show());
    }

    // ── 运行状态轮询（进度卡「运行状态」行）────────────────────
    // 三个数据源，全部只读、失败不炸界面：
    //   ① llama-server 的 /metrics（run.sh 用 --metrics 打开）：累计 token 计数做差 → 实时 tok/s
    //   ② /proc/<pid>/stat（pid 由 run.sh 写进 _llama.pid）：进程 CPU tick 差 ÷ 墙钟差 → 占用核数
    //   ③ /sys/.../scaling_cur_freq：八核实时频率，能看出有没有被热降频
    private Thread statsThread;
    private volatile boolean statsRunning = false;
    private volatile int statsGen = 0;   // 代际号：旧 post 不得覆盖新一轮/停止后的界面
    private volatile long cpuPrevTicks = -1L;
    private volatile long cpuPrevMs = 0L;
    private volatile double metPrevPrompt = -1.0, metPrevPredicted = -1.0, metPrevMs = 0.0;

    private static final int STATS_INTERVAL_MS = 2000;
    /** 运行日志保留行数上限；超出后丢最早的，并在首行留一句提示。 */
    private static final int LOG_MAX_LINES = 300;
    private static final String STATS_IDLE = "运行状态　空闲";

    private void startStats() {
        stopStats();
        cpuPrevTicks = -1L;
        cpuPrevMs = 0L;
        metPrevPrompt = -1.0;
        metPrevPredicted = -1.0;
        metPrevMs = 0.0;
        statsRunning = true;
        statsGen++;
        statsThread = new Thread(this::statsLoop, "perf-stats");
        statsThread.setDaemon(true);
        statsThread.start();
    }

    private void stopStats() {
        statsRunning = false;
        if (statsThread != null) {
            statsThread.interrupt();
            statsThread = null;
        }
        if (statView != null) {
            statView.setText(STATS_IDLE);
        }
    }

    private void statsLoop() {
        int fails = 0;
        // 先等 1.5s：刚点开始时 llama-server 还没起，[1/4] 提取音频阶段必然读不到
        try { Thread.sleep(1500L); } catch (InterruptedException e) { return; }
        while (!Thread.currentThread().isInterrupted()) {
            try {
                if (paused) {   // 冻结期间别把「已暂停」刷掉
                    final int genp = statsGen;
                    ui.post(() -> {
                        if (statView != null && statsRunning && genp == statsGen) {
                            statView.setText("运行状态　已暂停（点「继续」恢复）");
                        }
                    });
                    Thread.sleep(STATS_INTERVAL_MS);
                    continue;
                }
                updatePreview();
                final String text = collectStats();
                final int gen = statsGen;
                // 调试用：文本 + 三条数据源状态进 logcat，真机用 logcat 精确采样
                // （uiautomator 会被 2 秒一次的 setText 卡住 —— 窗口永不 idle，dump 拿不到）
                Log.i("AsmrStats", text + "  [metric=" + lastMetricErr
                        + " proc=" + lastProcErr + " freq=" + lastFreqOk + "]");
                ui.post(() -> {
                    if (statsRunning && gen == statsGen) applySegProgress();
                    if (statView != null && statsRunning && gen == statsGen) statView.setText(text);
                    // 队列页开着时同步那行状态（关着就是空转，代价可忽略）
                    if (queueLive != null && queueOverlay != null
                            && queueOverlay.getVisibility() == View.VISIBLE) {
                        syncQueueLive();
                    }
                });
                // collectStats 内部三路各自 catch、几乎不抛异常，所以「失败」必须看三路结果，
                // 不能只看异常：否则 fails 恒 0，线程永不自愈退出。
                // 判据放宽到 150 轮（约 5 分钟）：llama-server 冷启动加载 1.1GB 模型要 30~90 秒，
                // 这期间 /metrics 连不上，60 轮（2 分钟）会误杀整轮任务。
                boolean anyOk = "ok".equals(lastMetricErr) || "ok".equals(lastProcErr)
                        || lastFreqOk.matches("[0-9].*");
                if (anyOk) {
                    fails = 0;
                } else if (++fails > 150) {
                    Log.w("AsmrStats", "三路数据源连续 " + fails + " 轮全读不到，停轮询省电");
                    return;
                }
            } catch (Exception e) {
                if (++fails > 150) return;
            }
            try {
                Thread.sleep(STATS_INTERVAL_MS);
            } catch (InterruptedException e) {
                return;
            }
        }
    }

    /** 读 "done total" 形式的进度文件，返回 {done,total}；读不到返回 {-1,-1}。 */
    /** 组装「识别 k/N 段（x 段/分 · y× 实时，剩约 z 分）」；spm 太小时沿用上一次的值。 */
    private String segText(int done, int total, double spm) {
        StringBuilder b = new StringBuilder("运行状态　识别 ");
        b.append(done).append("/").append(total).append(" 段");
        double sp = spm > 0.5 ? spm : lastSpm;
        if (sp > 0.5) {
            int leftMin = (int) Math.ceil(Math.max(0, total - done) / sp);
            // 每段 20 秒音频（run.sh 的 SEG_SEC=20）：
            //   spm 段/分 → 每分钟消化 spm*20 秒音频 → 倍速 = spm*20/60
            b.append(String.format(java.util.Locale.ROOT,
                    "（%.0f 段/分 · %.1f× 实时，剩约 %d 分）",
                    sp, sp * 20.0 / 60.0, leftMin));
        }
        return b.toString();
    }

    private int[] readSegProgress(String name) {
        String pr = readOneLine(new File(binDir(), name).getAbsolutePath());
        if (pr != null) {
            String[] a = pr.trim().split("\\s+");
            if (a.length >= 2) {
                try {
                    return new int[]{Integer.parseInt(a[0]), Integer.parseInt(a[1])};
                } catch (Exception ignore) { }
            }
        }
        return new int[]{-1, -1};
    }

    /** 把「第几段 / 共几段」写到阶段文字，并让进度条按比例走（在 UI 线程调用）。 */
    private void applySegProgress() {
        if (!statsRunning || segPhase == null || segTotal <= 0 || segDone < 0) return;
        float r = Math.min(1f, segDone / (float) segTotal);
        if ("asr".equals(segPhase)) {
            setStage("当前阶段　语音识别 " + segDone + "/" + segTotal + " 段");
            // 0.18 起步（[2/4] 的位置）；主车道到 0.52，剩下的 0.10 留给补车道
            setFileProgress(0.18f + 0.34f * r, false);
        } else if ("sv".equals(segPhase)) {
            setStage("当前阶段　补车道 " + segDone + "/" + segTotal + " 窗");
            setFileProgress(0.52f + 0.10f * r, false);
        } else if ("mt".equals(segPhase)) {
            setStage("当前阶段　翻译 " + segDone + "/" + segTotal + " 段");
            setFileProgress(0.74f + 0.25f * r, false);
        }
    }

    /** 最近一轮三条数据源各自的成功情况，只进 logcat，不上界面。 */
    private String lastMetricErr = "-", lastProcErr = "-", lastFreqOk = "-";

    private String collectStats() throws Exception {
        StringBuilder sb = new StringBuilder();

        // 进程发现：识别阶段跑的是 libsherpaoffline.so，翻译阶段才是 libllamaserver.so。
        // 之前只看 _llama.pid，而翻译服务现在故意推迟到识别之后才起，
        // 于是整段识别期间都会显示「模型 未启动」+ 没有核数——用户以为卡死（实测反馈）。
        // _llama.pid 在上一次任务结束后不会删（翻译服务是刻意驻留复用的），
        // 所以它可能指向一个早就死掉的 pid：这时如果还当它活着，识别阶段就永远不会去扫 sherpa，
        // 界面整段显示「模型 未启动」（实测 ja150.wav 就踩到了）。必须先校验 pid 真的还在。
        // 翻译服务在上一次任务结束后会刻意驻留（复用，省 30~60 秒加载），
        // 于是识别阶段 llamaPid 也是「活的」，会盖住 sherpa，界面就显示成「模型 写0.0/读0 t/s」。
        // 必须先找 sherpa，它才是识别阶段真正干活的进程。
        long sherpaPid = findPidByCmdline("libsherpaoffline.so");
        long llamaPid = sherpaPid > 0 ? -1 : liveLlamaPid();

        // ① 模型吞吐：/metrics 是 Prometheus 文本，取两个累计计数做差
        //    注意 prompt_tokens_total 在 1.8B 模型上每秒可达数百，写成整数位
        String m = null;
        try {
            m = httpGet("http://127.0.0.1:18081/metrics");
            lastMetricErr = (m == null ? "HTTP非200" : (m.indexOf("prompt_tokens_total") >= 0 ? "ok" : "无metrics端点"));
        } catch (Exception e) {
            lastMetricErr = e.getClass().getSimpleName() + ":" + e.getMessage();
        }
        if (m != null && m.indexOf("prompt_tokens_total") >= 0 && fileProgress >= 0.62f) {
            int[] mp2 = readSegProgress("_mt_progress");
            if (mp2[1] > 0) {
                segPhase = "mt";
                segDone = mp2[0];
                segTotal = mp2[1];
            } else {
                segPhase = null;
            }
            double prompt = metricValue(m, "prompt_tokens_total");
            double predict = metricValue(m, "tokens_predicted_total");
            long now = System.currentTimeMillis();
            boolean restarted = metPrevPrompt >= 0 && (prompt < metPrevPrompt || predict < metPrevPredicted);
            if (prompt >= 0 && predict >= 0 && metPrevMs > 0 && now > metPrevMs && !restarted
                    && prompt >= metPrevPrompt && predict >= metPrevPredicted) {
                double dt = (now - metPrevMs) / 1000.0;
                double pps = (prompt - metPrevPrompt) / dt;
                double dps = (predict - metPrevPredicted) / dt;
                sb.append(String.format(java.util.Locale.ROOT,
                        "运行状态　模型 写%.1f/读%.0f t/s", dps, pps));
            } else if (restarted) {
                // 计数变小 = 服务重启过。基准必须重建，否则下一轮的 dt 会跨停机期，吞吐被摊薄
                sb.append("运行状态　模型 重启中");
            } else {
                sb.append("运行状态　模型 空闲");
            }
            // 翻译阶段的倍速：_mt_preview 最后一行的结束时间就是「已经翻到第几秒的音频」，
            // 除以翻译真正用掉的时间就是相对实时的倍数（用户 m08385 要求）。
            double audioEnd = lastPreviewEnd();
            if (audioEnd > 0) {
                if (mtStartMs == 0) mtStartMs = now;
                double elapsed = (now - mtStartMs) / 1000.0;
                if (elapsed > 3) {
                    sb.append(String.format(java.util.Locale.ROOT, "　%.1f× 实时（已到 %s）",
                            audioEnd / elapsed, fmtClock(String.valueOf(audioEnd))));
                }
            }
            // 无论走哪个分支都重建基准（restarted 时尤其重要）
            if (prompt >= 0) {
                metPrevPrompt = prompt;
                metPrevPredicted = predict;
                metPrevMs = now;
            }
        } else if (sherpaPid > 0) {
            // 识别阶段没有 llama 的 /metrics，速度改从 run.sh 写的进度文件算：
            //   _asr_progress = "<已完成段数> <总段数>"
            // 融合模式的补车道（一窗一进程补 SenseVoice）另写 _sv_progress，
            // 主车道的 _asr_progress 在补漏期间早就写满，不读它数字就完全不动。
            int[] svs = readSegProgress("_sv_progress");
            int[] pp = readSegProgress("_asr_progress");
            int done = pp[0], total = pp[1];
            if (svs[0] >= 0 && svs[1] > 0 && svs[0] < svs[1]) {
                segPhase = "sv";
                segDone = svs[0];
                segTotal = svs[1];
                sb.append("运行状态　补车道 ").append(svs[0]).append("/").append(svs[1])
                        .append(" 窗（SenseVoice 补漏，一窗一进程较慢）");
            } else {
            segPhase = "asr";
            segDone = done;
            segTotal = total;
            if (done >= 0 && total > 0) {
                long now = System.currentTimeMillis();
                if (asrSamples.isEmpty() || asrSamples.peekLast()[1] != done) {
                    asrSamples.addLast(new long[]{now, done});
                }
                while (asrSamples.size() > 2 && now - asrSamples.peekFirst()[0] > 60000L) {
                    asrSamples.removeFirst();
                }
                double spm = 0;
                long[] first = asrSamples.peekFirst();
                long[] last = asrSamples.peekLast();
                if (first != null && last != null && last[0] - first[0] >= 10000L
                        && last[1] > first[1]) {
                    spm = (last[1] - first[1]) / ((last[0] - first[0]) / 60000.0);
                }
                if (spm > 0.5) lastSpm = spm;
                lastAsrDone = done;
                lastAsrTotal = total;
                sb.append(segText(done, total, spm));
            } else if (lastAsrTotal > 0) {
                // 进度文件还没写出来，先沿用上一轮的读数，别突然变成没有速度
                sb.append(segText(lastAsrDone, lastAsrTotal, 0));
            } else {
                sb.append("运行状态　识别中（翻译服务识别完才加载）");
            }
            }
        } else {
            segPhase = null;
            // 没有推理进程在跑 ≠ 没在干活：ffmpeg 提取音频、模型加载间隙都是这个状态。
            // 以前一律写「模型 未启动」，用户以为任务压根没启动（实测反馈），按进度区间说清楚。
            if (!busy) {
                sb.append("运行状态　空闲");
            } else if (fileProgress < 0.18f) {
                sb.append("运行状态　正在提取音频（ffmpeg）");
            } else if (fileProgress < 0.62f && lastAsrTotal > 0) {
                // 批次间隙 sherpa 退出重启，读数照旧，速度不许闪
                sb.append(segText(lastAsrDone, lastAsrTotal, 0));
            } else if (fileProgress < 0.62f) {
                sb.append("运行状态　正在启动识别模型…");
            } else if (fileProgress < 0.74f) {
                sb.append("运行状态　正在加载翻译模型（约 2 GB，要 30~90 秒）");
            } else {
                sb.append("运行状态　正在生成字幕…");
            }
        }

        // ② 进程存活探测。以前这里还算「占用几核」并显示，用户反馈占屏幕位置，已去掉显示；
        //    仍探一次 /proc/<pid>/stat，因为 statsLoop 的「三路都失败就自愈退出」靠 lastProcErr 判断。
        try {
            long pid = llamaPid > 0 ? llamaPid : sherpaPid;
            if (pid > 0) {
                String st = readOneLine("/proc/" + pid + "/stat");
                lastProcErr = (st == null ? "读不到 /proc/" + pid + "/stat" : "ok");
            } else {
                lastProcErr = "找不到推理进程";
            }
        } catch (Exception ignore) { }

        // ③ 实时频率：cpu4-7 是大核簇，cpu0-3 是小核簇（天玑 1000+ 布局）
        int min = 0, max = 0;
        for (int i = 4; i < 8; i++) {
            try {
                String s = readOneLine("/sys/devices/system/cpu/cpu" + i + "/cpufreq/scaling_cur_freq");
                if (s == null) continue;
                long khz = Long.parseLong(s.trim());
                if (khz <= 0) continue;
                if (min == 0 || khz < min) min = (int) khz;
                if (khz > max) max = (int) khz;
            } catch (Exception ignore) { }
        }
        if (max > 0) {
            // 只报大核最高档就够了，写「2.6-2.6G」白占半行
            if (Math.abs(max - min) < 100000) {
                sb.append(String.format(java.util.Locale.ROOT, "  大核%.1fG", max / 1e6));
            } else {
                sb.append(String.format(java.util.Locale.ROOT, "  大核%.1f~%.1fG", min / 1e6, max / 1e6));
            }
            lastFreqOk = (min / 1e6) + "-" + (max / 1e6);
        } else {
            lastFreqOk = "none";
        }
        return sb.length() > 0 ? sb.toString() : STATS_IDLE;
    }

    /** Prometheus 文本里找 "llamacpp:<name> <value>" 行（精确匹配行尾名称，避免 cached_total 误中）。 */
    private static double metricValue(String body, String name) {
        for (String line : body.split("\n")) {
            if (line.startsWith("#")) continue;
            int sp = line.indexOf(' ');
            if (sp <= 0) continue;
            String key = line.substring(0, sp);
            int brace = key.indexOf('{');
            if (brace >= 0) key = key.substring(0, brace);   // llamacpp:name{model="x"} → llamacpp:name
            int colon = key.lastIndexOf(':');
            if (colon >= 0 && key.substring(colon + 1).equals(name)) {
                try {
                    return Double.parseDouble(line.substring(sp + 1).trim());
                } catch (NumberFormatException e) {
                    return -1.0;
                }
            }
        }
        return -1.0;
    }

    private static String httpGet(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(1500);
        c.setReadTimeout(1500);
        c.setRequestMethod("GET");
        try {
            if (c.getResponseCode() != 200) return null;
            java.io.InputStream in = c.getInputStream();
            try {
                byte[] buf = new byte[8192];
                StringBuilder sb = new StringBuilder();
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                return sb.toString();
            } finally {
                in.close();
            }
        } finally {
            c.disconnect();
        }
    }


    /** 后台检查 GitHub(主) / Gitee(备) 上的 latest.txt，发现新版本就弹提示。 */
    private void checkForUpdate(final boolean manual) {
        final Thread t = new Thread(() -> {
            String raw = null;
            Exception err = null;
            for (String base : new String[]{VERSION_URL, VERSION_URL_BACKUP}) {
                try { raw = httpGetLong(base); if (raw != null && !raw.isEmpty()) break; }
                catch (Exception e) { err = e; }
            }
            if (raw == null || raw.isEmpty()) {
                if (manual) ui.post(() -> toast("检查更新失败：无法连接更新服务器"));
                return;
            }
            final String fraw = raw;
            ui.post(() -> handleUpdateResult(fraw, manual));
        });
        t.setDaemon(true);
        t.start();
    }

    /** 解析 latest.txt 并决定是否提示。latest.txt 用简单 key=value / URL 两种形式都支持。 */
    private void handleUpdateResult(String raw, final boolean manual) {
        String remoteVer = null, apkUrl = null, note = null;
        for (String line : raw.split("\n")) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("#")) continue;
            if (line.regionMatches(true, 0, "VERSION=", 0, 8)) remoteVer = line.substring(8).trim();
            else if (line.regionMatches(true, 0, "APK=", 0, 4)) apkUrl = line.substring(4).trim();
            else if (line.regionMatches(true, 0, "NOTE=", 0, 5)) note = line.substring(5).trim();
            else if (line.regionMatches(true, 0, "MODEL_URL=", 0, 10)) remoteModelUrl = line.substring(10).trim();
            else if (line.regionMatches(true, 0, "MODEL_CODE=", 0, 11)) remoteModelCode = line.substring(11).trim();
            else if (remoteVer == null && !line.contains("=")) remoteVer = line; // 裸版本号
            else if (apkUrl == null && (line.startsWith("http"))) apkUrl = line;
        }
        if (remoteVer == null) return;
        String local = "";
        try { local = getPackageManager().getPackageInfo(getPackageName(), 0).versionName; }
        catch (Exception ignored) {}
        if (local.isEmpty()) return;
        boolean newer = versionNewer(remoteVer, local);
        // 同一新版本只提示一次（记住已提示的版本号）
        String lastNotify = getSharedPreferences("update", MODE_PRIVATE).getString("notified", "");
        if (newer && remoteVer.equals(lastNotify)) {
            if (manual) toast("已是最新版本（" + local + "）");
            return;
        }
        String msg;
        if (newer) {
            msg = "发现新版本 " + remoteVer + "（当前 " + local + "）\n"
                 + (note != null && !note.isEmpty() ? note + "\n" : "")
                 + "\n点击下方按钮到发布页下载安装包。";
            final String fApkUrl = apkUrl;
            final String fNav = "https://github.com/T2887/NihonSubs/releases/latest";
            android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
            b.setTitle("有新版本");
            b.setMessage(msg);
            b.setPositiveButton("去下载", (d, w) -> {
                d.dismiss();
                openUrl(fApkUrl != null ? fApkUrl : fNav);
            });
            b.setNegativeButton("以后再说", (d, w) -> d.dismiss());
            try {
                getSharedPreferences("update", MODE_PRIVATE).edit()
                    .putString("notified", remoteVer).apply();
                b.show();
            } catch (Exception ignored) {}
        } else if (manual) {
            toast("已是最新版本（" + local + "）");
        }
    }

    /** 只认识 dot 分隔的语义版本号;不支持 <major.minor> 之外时退回按字符串相等比较。 */
    private static boolean versionNewer(String a, String b) {
        if (a == null || b == null) return false;
        if (a.equals(b)) return false;
        String[] A = a.split("\\."), B = b.split("\\.");
        for (int i = 0; i < Math.max(A.length, B.length); i++) {
            long av = i < A.length ? parseLong(A[i]) : 0;
            long bv = i < B.length ? parseLong(B[i]) : 0;
            if (av > bv) return true;
            if (av < bv) return false;
        }
        return false;
    }
    private static long parseLong(String s) {
        try { return Long.parseLong(s); } catch (Exception e) { return 0; }
    }

    /** 「缺模型」引导：弹窗让用户去下载页取 asmr-models.zip（地址来自 latest.txt 的 MODEL_URL）。 */
    private void promptModelDownload() {
        if (importingModels) { toast("正在导入模型，请稍等"); return; }
        dismissModelDialog();
        // 模型已在位：这个按钮改成「重新导入」入口，而不是只弹下载页
        File md0 = modelDir();
        if (new File(md0, "model.int8.onnx").isFile() && new File(md0, "hy.gguf").isFile()) {
            showModelReadyDialog();
            return;
        }
        if (remoteModelUrl != null && !remoteModelUrl.isEmpty()) {
            showModelDlDialog(remoteModelUrl);
            return;
        }
        Thread t = new Thread(() -> {
            String url = null, code = null;
            for (String base : new String[]{VERSION_URL, VERSION_URL_BACKUP}) {
                try {
                    String raw = httpGetLong(base);
                    if (raw == null) continue;
                    for (String line : raw.split("\n")) {
                        line = line.trim();
                        if (line.regionMatches(true, 0, "MODEL_URL=", 0, 10)) {
                            url = line.substring(10).trim();
                        } else if (line.regionMatches(true, 0, "MODEL_CODE=", 0, 11)) {
                            code = line.substring(11).trim();
                        }
                    }
                    if (url != null && !url.isEmpty()) break;
                } catch (Exception ignored) { }
            }
            final String u = url, c = code;
            ui.post(() -> {
                if (u != null && !u.isEmpty()) {
                    remoteModelUrl = u;
                    remoteModelCode = c;
                    showModelDlDialog(u);
                } else {
                    startModelImport();   // 取不到线上地址就退回本地导入
                }
            });
        }, "model-url");
        t.setDaemon(true);
        t.start();
    }

    /** 复制文本到剪贴板（提取码用）。 */
    private void copyText(final String s, final String tip) {
        try {
            android.content.ClipboardManager cm = (android.content.ClipboardManager)
                    getSystemService(android.content.Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(android.content.ClipData.newPlainText("nihonsubs", s));
            if (tip != null) toast(tip);
        } catch (Exception ignored) { }
    }

    private void showModelDlDialog(final String url) {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle("需要先下载模型");
        final String code = remoteModelCode;
        boolean hasCode = code != null && !code.isEmpty();
        b.setMessage("首次使用需要模型包 asmr-models.zip（约 1.5 GB）。\n\n"
                + "点「去下载」用浏览器打开下载页"
                + (hasCode ? "，提取码：" + code + "（点下载会自动复制）" : "")
                + "；下载好后回到 App 选这个 zip，会自动解压安装。");
        b.setPositiveButton("去下载", (d, w) -> {
            d.dismiss();
            if (hasCode) copyText(code, "提取码 " + code + " 已复制");
            openUrl(url);
        });
        b.setNeutralButton("已下载，去导入", (d, w) -> { d.dismiss(); startModelImport(); });
        b.setNegativeButton("取消", (d, w) -> d.dismiss());
        dismissModelDialog();
        try {
            modelDialog = b.create();
            modelDialog.setOnDismissListener(d -> modelDialog = null);
            modelDialog.show();
        } catch (Exception ignored) { }
    }

    /** 关掉当前显示着的模型弹窗（不会影响其它弹窗）。 */
    private void dismissModelDialog() {
        final android.app.AlertDialog d = modelDialog;
        modelDialog = null;
        if (d != null) {
            try { d.dismiss(); } catch (Exception ignored) { }
        }
    }

    /** 模型已在位时的入口：重新导入 / 打开下载页。 */
    private void showModelReadyDialog() {
        android.app.AlertDialog.Builder b = new android.app.AlertDialog.Builder(this);
        b.setTitle("模型已导入");
        b.setMessage("model.int8.onnx 与 hy.gguf 都已就位，可以直接开始处理。\n\n"
                + "需要覆盖重装时点「重新导入」，选下载好的 asmr-models.zip（或单独选那两个文件）。");
        b.setPositiveButton("重新导入", (d, w) -> { d.dismiss(); startModelImport(); });
        if (remoteModelUrl != null && !remoteModelUrl.isEmpty()) {
            final String url = remoteModelUrl;
            b.setNeutralButton("下载页", (d, w) -> {
                d.dismiss();
                if (remoteModelCode != null && !remoteModelCode.isEmpty())
                    copyText(remoteModelCode, "提取码 " + remoteModelCode + " 已复制");
                openUrl(url);
            });
        }
        b.setNegativeButton("关闭", (d, w) -> d.dismiss());
        dismissModelDialog();
        try {
            modelDialog = b.create();
            modelDialog.setOnDismissListener(d -> modelDialog = null);
            modelDialog.show();
        } catch (Exception ignored) { }
    }

    private void openUrl(String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            startActivity(i);
        } catch (Exception e) { toast("无法打开链接"); }
    }

    private void toast(final String s) {
        ui.post(() -> {
            try {
                android.widget.Toast.makeText(getApplicationContext(), s,
                        android.widget.Toast.LENGTH_LONG).show();
            } catch (Exception ignored) {}
        });
    }


    /** 版本检查用：超时拉长到 6s，公网慢也等得起。 */
    private static String httpGetLong(String url) throws Exception {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        c.setConnectTimeout(6000);
        c.setReadTimeout(6000);
        c.setRequestMethod("GET");
        c.setInstanceFollowRedirects(true);
        try {
            if (c.getResponseCode() != 200) return null;
            java.io.InputStream in = c.getInputStream();
            try {
                byte[] buf = new byte[8192];
                StringBuilder sb = new StringBuilder();
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, "UTF-8"));
                return sb.toString();
            } finally { in.close(); }
        } finally { c.disconnect(); }
    }

    private static String readOneLine(String p) {
        try (java.io.FileInputStream in = new java.io.FileInputStream(p)) {
            byte[] buf = new byte[2048];
            int n = in.read(buf);
            if (n <= 0) return null;
            return new String(buf, 0, n, "UTF-8").trim();
        } catch (Exception e) {
            return null;
        }
    }

    /** 读 pid 文件（内容可能带参数），失败返回 -1。 */
    private static long readPidFile(String p) {
        String s = readOneLine(p);
        if (s == null || s.isEmpty()) return -1;
        try { return Long.parseLong(s.trim().split("\\s+")[0]); } catch (Exception e) { return -1; }
    }

    /** 读 _llama.pid 并校验那个 pid 现在确实是翻译服务；进程已退/被复用就返回 -1。 */
    private long liveLlamaPid() {
        long pid = readPidFile(new File(binDir(), "_llama.pid").getAbsolutePath());
        if (pid <= 0) return -1;
        String c = readOneLine("/proc/" + pid + "/cmdline");
        return (c != null && c.contains("libllamaserver")) ? pid : -1;
    }

    /** 扫 /proc/<pid>/cmdline 找进程（只用于界面展示，找不到就返回 -1）。 */
    private static long findPidByCmdline(String needle) {
        java.io.File[] dirs = new java.io.File("/proc").listFiles();
        if (dirs == null) return -1;
        for (java.io.File d : dirs) {
            String name = d.getName();
            if (name.isEmpty() || !Character.isDigit(name.charAt(0))) continue;
            String cmd = readOneLine(d.getPath() + "/cmdline");
            // cmdline 用 \0 分隔，直接整串 contains 判断即可（旧版本写成含 NUL 的整串）
            if (cmd != null && cmd.contains(needle)) {
                try { return Long.parseLong(name); } catch (Exception ignore) { }
            }
        }
        return -1;
    }

    /** 阶段文字淡出→换 →淡入，避免硬切跳变。 */
    private void setStage(final String text) {
        if (stageView == null) return;
        // 后台线程（模型解压、流水线读取）会调到这里，动画只能在主线程跑
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(() -> setStage(text));
            return;
        }
        if (text.contentEquals(stageView.getText())) return;
        stageView.animate().cancel();
        stageView.animate().alpha(0f).setDuration(120)
                .withEndAction(() -> {
                    stageView.setText(text);
                    if (notifiedStage.length() > 0) updateKeepAlive(text, notifiedPct < 0 ? 0 : notifiedPct);
                    stageView.animate().alpha(1f).setDuration(180).start();
                })
                .start();
    }

    /** 拉起前台服务：把进程提到前台优先级，切后台不会被回收。 */
    private void startKeepAlive() {
        Intent it = new Intent(this, KeepAliveService.class);
        it.setAction(KeepAliveService.ACTION_START);
        it.putExtra("stage", "准备中");
        it.putExtra("pct", 0);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(it);
        } else {
            startService(it);
        }
    }

    private void stopKeepAlive() {
        Intent it = new Intent(this, KeepAliveService.class);
        it.setAction(KeepAliveService.ACTION_STOP);
        startService(it);
    }

    /** 更新通知里的阶段/进度（百分比变化不足 3% 就不打扰，避免刷屏）。 */
    private void updateKeepAlive(String stage, int pct) {
        boolean stageChanged = stage != null && !stage.equals(notifiedStage);
        if (!stageChanged && Math.abs(pct - notifiedPct) < 3) return;
        notifiedPct = pct;
        if (stageChanged) notifiedStage = stage;
        Intent it = new Intent(this, KeepAliveService.class);
        it.setAction(KeepAliveService.ACTION_START);
        it.putExtra("pct", pct);
        it.putExtra("stage", notifiedStage);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            startForegroundService(it);
        } else {
            startService(it);
        }
    }

    /** 单个文件的进度 → 换算成整批的总进度（第 i/N 个文件）。 */
    private void setFileProgress(float p, boolean animate) {
        fileProgress = Math.max(fileProgress, Math.min(1f, p));
        int n = Math.max(1, picked.size());
        setProgress((curIdx + fileProgress) / n, animate);
    }

    private void setProgress(float target, boolean animate) {
        float to = Math.max(progress, Math.min(1f, target));
        if (progressAnim != null) progressAnim.cancel();
        if (!animate) {
            progress = to;
            applyProgress();
            return;
        }
        progressAnim = ValueAnimator.ofFloat(progress, to);
        progressAnim.setDuration(360);
        progressAnim.setInterpolator(new DecelerateInterpolator());
        progressAnim.addUpdateListener(a -> {
            progress = (float) a.getAnimatedValue();
            applyProgress();
        });
        progressAnim.start();
    }

    private void applyProgress() {
        int w = progressTrack.getWidth();
        ViewGroup.LayoutParams lp = progressFill.getLayoutParams();
        lp.width = (int) (w * progress);
        progressFill.setLayoutParams(lp);
        percentView.setText(Math.round(progress * 100f) + "%");
        updateKeepAlive(null, Math.round(progress * 100f));

        // 跑完时：进度条转绿 + 一次轻微的纵向脉冲，作为「完成」的反馈
        boolean done = progress >= 0.999f;
        if (done != progressWasDone) {
            progressWasDone = done;
            progressFill.setBackground(round(done ? OK_GREEN : ACCENT, 5, 0, 0));
            if (done) {
                progressFill.setScaleY(1f);
                progressFill.animate().scaleY(2.2f).setDuration(140)
                        .withEndAction(() -> progressFill.animate()
                                .scaleY(1f).setDuration(180).start())
                        .start();
            }
        }
    }

    /** 把「运行日志」里现有内容导出成 txt 存到 Download 目录，方便发给开发者排查 */
    private void exportLog() {
        String text = logView == null ? "" : logView.getText().toString();
        if (text.trim().isEmpty()) { toast("日志是空的"); return; }
        String name = "nihonsubs-log-"
                + new java.text.SimpleDateFormat("MMdd-HHmmss", java.util.Locale.ROOT)
                        .format(new java.util.Date()) + ".txt";
        java.io.File dir = new java.io.File(android.os.Environment.getExternalStorageDirectory(), "Download");
        java.io.File f = new java.io.File(dir, name);
        try {
            if (!dir.isDirectory()) dir.mkdirs();
            java.io.FileOutputStream out = new java.io.FileOutputStream(f);
            out.write(text.getBytes("UTF-8"));
            out.flush();
            out.close();
            appendLog("[日志] 已导出：" + f.getAbsolutePath());
            toast("已导出到 Download/" + name);
        } catch (Exception e) {
            toast("导出失败：" + e.getMessage());
        }
    }

    private void appendLog(String line) {
        // runPipeline 在后台线程调用：直接改 View 会抛 CalledFromWrongThreadException，
        // 一抛就把整条流水线的异常路径带歪（日志只剩第一行），所以统一切回主线程。
        if (Looper.myLooper() != Looper.getMainLooper()) {
            ui.post(() -> appendLog(line));
            return;
        }
        CharSequence old = logView.getText();
        String text = old.length() == 0 ? line : (old + "\n" + line);
        String[] lines = text.split("\n");
        // 日志只留最近的 LOG_MAX_LINES 行：跑长片子时日志会一直涨，
        // 全量留在 TextView 里既拖慢重绘，也让「导出」出来一大坨没人看。
        if (lines.length > LOG_MAX_LINES) {
            final int keep = LOG_MAX_LINES - 1;   // 留一行给提示
            StringBuilder sb = new StringBuilder("…（较早的日志已自动清理，只保留最近 ")
                    .append(keep).append(" 行，完整记录点「导出」）");
            for (int k = lines.length - keep; k < lines.length; k++) {
                sb.append('\n').append(lines[k]);
            }
            text = sb.toString();
        }
        logView.setText(text);
        logScroll.post(() -> logScroll.fullScroll(View.FOCUS_DOWN));
    }

    @Override
    protected void onDestroy() {
        timeHandler.removeCallbacksAndMessages(null);
        stop();
        super.onDestroy();
    }
}
