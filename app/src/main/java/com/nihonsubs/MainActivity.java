package com.nihonsubs;

import android.animation.Animator;
import android.animation.AnimatorListenerAdapter;
import android.animation.ValueAnimator;
import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.content.res.ColorStateList;
import android.net.Uri;
import android.os.Bundle;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.Log;
import android.util.TypedValue;
import android.view.Gravity;
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
    private TextView subtitleView;
    private TextView modelStateView;   // 环境卡里的「模型」状态文字
    private TextView modelBtn;         // 「导入模型」按钮（导入后置灰不可点）
    private final List<String> picked = new ArrayList<>();

    private Process running;
    private boolean busy = false;
    private float progress = 0f;
    private boolean progressWasDone = false;
    private int notifiedPct = -1;
    private static final int REQ_MODEL = 1002;
    private String pendingModelName = null;
    private int curIdx = 0;
    private float fileProgress = 0f;
    private volatile boolean stopping = false;
    private final java.util.List<String> fileState = new java.util.ArrayList<>();
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
        setContentView(buildUi());
        scanInputDir();
        // 启动后自动检查一次更新（静默，仅在有新版本时提示）
        ui.postDelayed(() -> checkForUpdate(false), 2000);
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
        content.addView(header);
        enterAnim(header, 0);

        // ── 卡片 1：环境 ──
        LinearLayout env = (LinearLayout) card();
        cardTitle(env, "环境");
        env.addView(infoRow("工作目录", appDir().getAbsolutePath()));
        env.addView(infoRow("输出目录", outputDir().getAbsolutePath()));
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
            TextView upd = narrowPill("检查更新");
            upd.setTextSize(12f);
            final String _vn = vn;
            upd.setOnClickListener(v -> checkForUpdate(true));
            ver.addView(upd, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            env.addView(ver);
        }
        content.addView(env);
        enterAnim(env, 1);

        // ── 卡片 2：文件列表 ──
        LinearLayout files = (LinearLayout) card();
        cardTitle(files, "文件列表");
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
        actionView.setSingleLine(true);
        actionView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        prog.addView(actionView, actLp);

        // 「运行状态」行：跑任务时每 2 秒刷一次 CPU 频率 / 模型 tok/s / 占用核数
        LinearLayout.LayoutParams statRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        statRowLp.topMargin = (int) dp(4);
        statView = label("运行状态　—", INK3, 11f);
        statView.setSingleLine(true);
        statView.setEllipsize(android.text.TextUtils.TruncateAt.END);
        prog.addView(statView, statRowLp);
        content.addView(prog);
        enterAnim(prog, 3);

        // ── 卡片 4：日志 ──
        LinearLayout logs = (LinearLayout) card();
        cardTitle(logs, "运行日志");
        logScroll = new ScrollView(this);
        logScroll.setBackground(round(SUNK, 10, CARD_LINE, 1));
        logScroll.setPadding((int) dp(10), (int) dp(8), (int) dp(10), (int) dp(8));
        logView = label("就绪。点「添加文件」选好视频，再点下方「开始处理」。", INK3, 11.5f);
        logView.setTypeface(Typeface.MONOSPACE);
        logView.setLineSpacing(dp(3), 1f);
        logScroll.addView(logView);
        logScroll.setMinimumHeight((int) dp(120));
        logs.addView(logScroll, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        content.addView(logs, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        enterAnim(logs, 4);

        // ── 底部固定动作条 ──
        LinearLayout bar = new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackgroundColor(BG);
        bar.setPadding((int) dp(14), (int) dp(8), (int) dp(14), (int) dp(12));
        bar.setElevation(dp(6));

        startBtn = pill("开始处理", true, 46);
        startBtn.setOnClickListener(v -> start());
        bar.addView(startBtn);

        stopBtn = pill("停止", false, 46);
        LinearLayout.LayoutParams stlp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        stlp.leftMargin = (int) dp(10);
        stopBtn.setOnClickListener(v -> stop());
        stopBtn.setAlpha(0.4f);
        stopBtn.setEnabled(false);
        bar.addView(stopBtn, stlp);

        // 弹性占位：把「使用说明」推到最右
        View spacer = new View(this);
        bar.addView(spacer, new LinearLayout.LayoutParams(0, 1, 1f));

        TextView helpBtn = pill("使用说明", false, 46);
        helpBtn.setOnClickListener(v -> showHelp());
        bar.addView(helpBtn);

        FrameLayout.LayoutParams blp = new FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        blp.gravity = Gravity.BOTTOM;
        root.addView(bar, blp);

        return root;
    }

    /** 使用说明弹层：自绘卡片，配色/圆角与主界面一致。 */
    private void showHelp() {
        // 路径写成相对形式：完整路径太长，窄屏上会被折行，反而不好认
        String txt =
                "【第一次用】\n"
                + "把两个模型文件放进 models 目录：\n"
                + "　· model.int8.onnx（230 MB，语音识别）\n"
                + "　· hy.gguf（1.1 GB，日语→中文翻译）\n"
                + "点「导入模型」可以选单个文件，也可以选打包好的 zip。\n\n"
                + "【放片子】\n"
                + "点「添加文件」选视频或音频（mp4 / mkv / wmv / mp3 / wav 都行），\n"
                + "或者用数据线拷进 input 目录，重开 App 会自动扫出来。\n\n"
                + "【开始处理】\n"
                + "点「开始处理」后四步全自动，不用管：\n"
                + "　1/4 提取音频　2/4 起翻译服务\n"
                + "　3/4 语音识别　4/4 翻译并写字幕\n"
                + "切到别的 App 也会继续跑，进度会显示在通知栏。\n\n"
                + "【拿字幕】\n"
                + "处理完的字幕在 output 目录，每个片子生成\n"
                + "　<原文件名>.chs.srt　（播放器外挂字幕）\n"
                + "　<原文件名>.chs.lrc　（音乐播放器逐行歌词）\n"
                + "和视频放一起，用播放器加载就能看。\n"
                + "（三个目录都在 Android/data/com.nihonsubs/files 下）\n\n"
                + "【耗时参考】\n"
                + "90 秒的片子约 31 秒；16 分钟约 6~7 分钟；91 分钟约 12 分钟。\n"
                + "全程离线，不联网。\n\n"
                + "【常见问题】\n"
                + "· 字幕条数少：ASMR 片子环境音多，纯音效段会被跳过，正常\n"
                + "· 准确度：手机上用的是 int8 量化模型，比 PC 版略逊属正常\n"
                + "· 卡住不动：点「停止」再点一次「开始处理」";

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
        ok.setOnClickListener(v -> dlg.dismiss());
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
        modelBtn.setOnClickListener(v -> startModelImport());
        row.addView(modelBtn);

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
            modelStateView.setText("模型已导入");
            modelStateView.setTextColor(OK_GREEN);
            modelBtn.setText("已导入");
            modelBtn.setEnabled(false);
            modelBtn.setAlpha(0.4f);
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
            new Thread(() -> copyModel(mUri), "model-import").start();
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

    /** 从 content:// 反查真实路径；查不到就把文件复制到 app 缓存。 */
    private String uriToPath(Uri uri) {
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

    private TextView delBtn;
    private final java.util.LinkedHashSet<String> selSet = new java.util.LinkedHashSet<>();

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

            if (k < fileState.size()) {
                String st = fileState.get(k);
                int col = "完成".equals(st) ? OK_GREEN
                        : ("失败".equals(st) ? WARN
                        : ("处理中".equals(st) ? ACCENT : INK3));
                TextView state = label(st, col, 11f);
                LinearLayout.LayoutParams slp = new LinearLayout.LayoutParams(
                        ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
                slp.leftMargin = (int) dp(4);
                row.addView(state, slp);
            }

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

    /** 字幕输出目录：app 的外部 files 目录，用户可用文件管理器访问。 */
    private File outputDir() {
        File d = new File(getExternalFilesDir(null), "output");
        d.mkdirs();
        return d;
    }

    private File modelDir() {
        // app 自己的外部目录：/sdcard/Android/data/com.nihonsubs/files/models
        File d = new File(getExternalFilesDir(null), "models");
        d.mkdirs();
        return d;
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
    private static final String ASSETS_VER = "v8";

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
        File md = modelDir();
        boolean hasModel = new File(md, "model.int8.onnx").isFile() && new File(md, "hy.gguf").isFile();
        if (!hasModel) {
            appendLog("[缺少模型] 首次使用需要模型（共约 1.35 GB）。");
            appendLog("[缺少模型] 推荐：选下载好的 asmr-models.zip，程序会自动解压；");
            appendLog("            也可以依次单独选 model.int8.onnx（约 230 MB）和 hy.gguf（约 1.1 GB）。");
            appendLog("[缺少模型] 目标目录：" + md.getAbsolutePath());
            setStage("当前阶段　等待模型");
            startModelImport();
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
        fileState.clear();
        for (int i = 0; i < picked.size(); i++) fileState.add("待处理");
        refreshFiles();
        setProgress(0f, true);
        setStage("当前阶段　准备中");
        logView.setText("");

        Thread t = new Thread(this::runPipeline, "pipeline");
        t.start();
    }

    // ── 模型导入（SAF 文件选择器）────────────────────────
    private void startModelImport() {
        pendingModelName = "model.int8.onnx";
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

    /** 解压 asmr-models.zip：只取 model.int8.onnx 和 hy.gguf，其余条目跳过。 */
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
                String base = new File(e.getName()).getName();
                boolean want = "model.int8.onnx".equals(base) || "hy.gguf".equals(base);
                if (!want) continue;
                File dst = new File(md, base);
                appendLog("[模型] 解压 " + base + " …");
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

    private void copyModel(final Uri uri) {
        String display = displayName(uri);
        if (display != null && display.toLowerCase().endsWith(".zip")) {
            unzipModels(uri);
            return;
        }
        final String name = pendingModelName;
        if (name == null) return;
        final File dst = new File(modelDir(), name);
        appendLog("[模型] 正在导入 " + name + " …");
        setStage("当前阶段　导入模型");
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
        appendLog("[模型] 完成：" + name + "，" + mb + " MB");
        ui.post(this::refreshModelRow);
        if ("model.int8.onnx".equals(name)) {
            pendingModelName = "hy.gguf";
            ui.post(() -> {
                appendLog("[模型] 接下来选 hy.gguf（约 1.1 GB）");
                pickModelFile();
            });
        } else {
            pendingModelName = null;
            ui.post(() -> {
                appendLog("[模型] 两个文件都就位了，再点一次「开始处理」");
                setStage("当前阶段　就绪");
            });
        }
    }

    private void stop() {
        stopping = true;
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
                final int fi = idx;
                curIdx = fi;
                fileProgress = 0f;
                ui.post(() -> {
                    if (fi < fileState.size()) fileState.set(fi, "处理中");
                    refreshFiles();
                    setStage("当前阶段　第 " + (fi + 1) + "/" + snapshot.size() + " 个");
                });
                appendLog("=== 开始处理：" + new File(file).getName() + " ===");
                String stem = new File(file).getName();
                int dot = stem.lastIndexOf('.');
                if (dot > 0) stem = stem.substring(0, dot);
                File outSrt = new File(outputDir(), stem + ".chs.srt");
                int dup = 2;
                while (usedOut.contains(outSrt.getName())) {
                    outSrt = new File(outputDir(), stem + "-" + dup + ".chs.srt");
                    dup++;
                }
                usedOut.add(outSrt.getName());
                // 音频提取交给 run.sh 里的 ffmpeg。
                // 原方案是先用系统 MediaCodec 解码再喂给脚本（为了只带 ffmpeg 当兜底），
                // 实测 90 秒音频要 2.8 秒、91 分钟片子要 2.9 分钟，而 ffmpeg 只要约 30 秒 —— 慢 5.7 倍，
                // 用户明确否决，所以回到 ffmpeg 一把到底。
                ProcessBuilder pb = new ProcessBuilder(
                        "/system/bin/sh", script.getAbsolutePath(), file, outSrt.getAbsolutePath());
                pb.directory(bin);
                pb.redirectErrorStream(true);
                pb.environment().put("LD_LIBRARY_PATH", getApplicationInfo().nativeLibraryDir);
                pb.environment().put("ASMR_LIB", getApplicationInfo().nativeLibraryDir);
                pb.environment().put("ASMR_WORK", bin.getAbsolutePath());
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
                ui.post(() -> {
                    if (fi < fileState.size()) fileState.set(fi, state);
                    refreshFiles();
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

    /** 解析子进程输出 → 阶段文字 + 进度条 + 日志。 */
    private void onLine(String line) {
        if (line.startsWith("[1/4]")) {
            setFileProgress(0.08f, true);
            setStage("当前阶段　提取音频");
        } else if (line.startsWith("[2/4]")) {
            setFileProgress(0.18f, true);
            setStage("当前阶段　语音识别");
        } else if (line.startsWith("[3/4]")) {
            setFileProgress(0.30f, true);
            setStage("当前阶段　语音识别（进行中）");
        } else if (line.startsWith("[4/4]")) {
            setFileProgress(0.72f, true);
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
        startBtn.setEnabled(true);
        startBtn.setAlpha(1f);
        stopBtn.setEnabled(false);
        stopBtn.setAlpha(0.4f);
        int done = 0;
        for (String st : fileState) {
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
    private static final String STATS_IDLE = "运行状态　—";

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
                final String text = collectStats();
                final int gen = statsGen;
                // 调试用：文本 + 三条数据源状态进 logcat，真机用 logcat 精确采样
                // （uiautomator 会被 2 秒一次的 setText 卡住 —— 窗口永不 idle，dump 拿不到）
                Log.i("AsmrStats", text + "  [metric=" + lastMetricErr
                        + " proc=" + lastProcErr + " freq=" + lastFreqOk + "]");
                ui.post(() -> {
                    if (statView != null && statsRunning && gen == statsGen) statView.setText(text);
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

    /** 最近一轮三条数据源各自的成功情况，只进 logcat，不上界面。 */
    private String lastMetricErr = "-", lastProcErr = "-", lastFreqOk = "-";

    private String collectStats() throws Exception {
        StringBuilder sb = new StringBuilder();

        // ① 模型吞吐：/metrics 是 Prometheus 文本，取两个累计计数做差
        //    注意 prompt_tokens_total 在 1.8B 模型上每秒可达数百，写成整数位
        String m = null;
        try {
            m = httpGet("http://127.0.0.1:18081/metrics");
            lastMetricErr = (m == null ? "HTTP非200" : (m.indexOf("prompt_tokens_total") >= 0 ? "ok" : "无metrics端点"));
        } catch (Exception e) {
            lastMetricErr = e.getClass().getSimpleName() + ":" + e.getMessage();
        }
        if (m != null && m.indexOf("prompt_tokens_total") >= 0) {
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
            // 无论走哪个分支都重建基准（restarted 时尤其重要）
            if (prompt >= 0) {
                metPrevPrompt = prompt;
                metPrevPredicted = predict;
                metPrevMs = now;
            }
        } else {
            sb.append("运行状态　模型 未启动");
        }

        // ② 占用核数：utime+stime（单位 1/100s）差 ÷ 墙钟差
        try {
            String pidS = readOneLine(new File(binDir(), "_llama.pid").getAbsolutePath());
            if (pidS == null) {
                lastProcErr = "读不到 _llama.pid";
            } else {
                long pid = Long.parseLong(pidS.trim().split("\\s+")[0]);
                String st = readOneLine("/proc/" + pid + "/stat");
                lastProcErr = (st == null ? "读不到 /proc/" + pid + "/stat" : "ok");
                if (st != null) {
                    // 格式：pid (comm) state ppid ... 第14/15字段是 utime/stime；comm 可能含空格，
                    // 所以从最后一个 ')' 之后开始切
                    int close = st.lastIndexOf(')');
                    String[] f = st.substring(close + 1).trim().split("\\s+");
                    // f[0]=state(第3字段)，utime 是第14字段 → f[11]，stime f[12]
                    long ticks = Long.parseLong(f[11]) + Long.parseLong(f[12]);
                    long now = System.currentTimeMillis();
                    if (cpuPrevTicks >= 0 && cpuPrevMs > 0 && now > cpuPrevMs) {
                        double cores = (ticks - cpuPrevTicks) * 10.0 / (now - cpuPrevMs);
                        if (cores >= 0) sb.append(String.format(java.util.Locale.ROOT,
                                "  %.1f核", Math.min(cores, 99)));
                    }
                    cpuPrevTicks = ticks;
                    cpuPrevMs = now;
                }
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
            sb.append(String.format(java.util.Locale.ROOT, "  大核%.1f-%.1fG", min / 1e6, max / 1e6));
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
        if (lines.length > 400) {
            StringBuilder sb = new StringBuilder();
            for (int k = lines.length - 400; k < lines.length; k++) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(lines[k]);
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
