package com.linguakey.keyboard.latin.settings;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.OpenableColumns;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.os.Build;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import com.linguakey.keyboard.R;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.linguakey.keyboard.latin.offline.LinguaKeyOfflineLibraryRepository;
import com.linguakey.keyboard.latin.offline.LinguaKeyOfflineLibraryRepository.LibraryImportResult;
import com.linguakey.keyboard.latin.offline.LinguaKeyOfflineLibraryRepository.LibraryMeta;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Arabic Settings UI for managing Modular Offline Translation JSON Libraries.
 * - App ships empty (0 bundled libraries).
 * - Supports importing MULTIPLE JSON libraries for the SAME language pair.
 * - Independent Enable/Disable switch & Delete button per library.
 */
public final class LinguaKeyOfflineLibrariesActivity extends Activity {
    private static final int REQ_IMPORT_LIB = 55201;

    private LinguaKeyOfflineLibraryRepository mRepo;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private int mBgColor;
    private int mCardColor;
    private int mStrokeColor;
    private int mTextPrimary;
    private int mTextSecondary;
    private int mAccentColor;

    private TextView mStatsTv;
    private TextView mEmptyTv;
    private ListView mListView;
    private LibrariesAdapter mAdapter;
    private final List<LibraryMeta> mItems = new ArrayList<>();

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRepo = LinguaKeyOfflineLibraryRepository.getInstance(this);
        resolveTheme();

        ActionBar ab = getActionBar();
        if (ab != null) {
            ab.setTitle("مكتبات الترجمة بدون إنترنت");
            ab.setDisplayHomeAsUpEnabled(true);
            GradientDrawable abBg = new GradientDrawable();
            abBg.setColor(mBgColor);
            ab.setBackgroundDrawable(abBg);
            ab.setElevation(0);
        } else {
            setTitle("مكتبات الترجمة بدون إنترنت");
        }

        buildUi();
        refreshList();
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            finish();
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    protected void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    private void resolveTheme() {
        int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        boolean isDark = (nightMode == Configuration.UI_MODE_NIGHT_YES);
        try {
            mBgColor = getResources().getColor(R.color.settings_bg);
            mCardColor = getResources().getColor(R.color.settings_card_bg);
            mStrokeColor = getResources().getColor(R.color.settings_card_stroke);
            mTextPrimary = getResources().getColor(R.color.settings_text_primary);
            mTextSecondary = getResources().getColor(R.color.settings_text_secondary);
            mAccentColor = getResources().getColor(R.color.settings_accent);
        } catch (Throwable ignored) {
            mBgColor = isDark ? 0xFF121316 : 0xFFF5F6FA;
            mCardColor = isDark ? 0xFF1E2026 : 0xFFFFFFFF;
            mStrokeColor = isDark ? 0xFF323640 : 0xFFDADCE0;
            mTextPrimary = isDark ? 0xFFE8EAED : 0xFF1F1F1F;
            mTextSecondary = isDark ? 0xFF9AA0A6 : 0xFF5F6368;
            mAccentColor = 0xFF0D6EFD;
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window w = getWindow();
            w.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            w.setStatusBarColor(mBgColor);
            w.setNavigationBarColor(mBgColor);
        }
    }

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(mBgColor);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setFitsSystemWindows(true);
        final int padH = dp(14);
        final int padV = dp(12);
        root.setPadding(padH, padV, padH, padV);

        root.setOnApplyWindowInsetsListener(new View.OnApplyWindowInsetsListener() {
            @Override
            public WindowInsets onApplyWindowInsets(final View v, final WindowInsets insets) {
                int topInset = insets.getSystemWindowInsetTop();
                int bottomInset = insets.getSystemWindowInsetBottom();
                int actionBarH = 0;
                TypedValue tv = new TypedValue();
                if (getTheme().resolveAttribute(android.R.attr.actionBarSize, tv, true)) {
                    actionBarH = TypedValue.complexToDimensionPixelSize(tv.data, getResources().getDisplayMetrics());
                }
                int safeTop = Math.max(padV, topInset + (getActionBar() != null ? dp(8) : 0));
                if (getActionBar() != null && topInset > 0 && topInset < actionBarH) {
                    safeTop = topInset + actionBarH + dp(8);
                }
                v.setPadding(padH, safeTop, padH, Math.max(padV, bottomInset + dp(8)));
                return insets;
            }
        });

        TextView infoTv = new TextView(this);
        infoTv.setText("يمكنك استيراد مكتبة واحدة أو عدة مكتبات JSON لنفس اللغة للعمل بالترجمة الفورية وترجمة الشاشة بدون إنترنت.");
        infoTv.setTextColor(mTextSecondary);
        infoTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        infoTv.setPadding(dp(2), 0, dp(2), dp(10));
        root.addView(infoTv);

        Button importBtn = new Button(this);
        importBtn.setText("+ استيراد مكتبة ترجمة (JSON)");
        importBtn.setAllCaps(false);
        importBtn.setTextColor(Color.WHITE);
        importBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        importBtn.setTypeface(Typeface.DEFAULT_BOLD);
        importBtn.setBackground(makeRounded(mAccentColor, mAccentColor, dp(10)));
        LinearLayout.LayoutParams btnLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(44));
        btnLp.bottomMargin = dp(10);
        importBtn.setLayoutParams(btnLp);
        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                launchImportPicker();
            }
        });
        root.addView(importBtn);

        mStatsTv = new TextView(this);
        mStatsTv.setTextColor(mTextSecondary);
        mStatsTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        mStatsTv.setPadding(dp(2), 0, dp(2), dp(8));
        root.addView(mStatsTv);

        mEmptyTv = new TextView(this);
        mEmptyTv.setText("التطبيق حالياً بدون مكتبات محلية.\nاضغط \"+ استيراد مكتبة ترجمة (JSON)\" لإضافة مكتباتك للعمل بدون نت.");
        mEmptyTv.setTextColor(mTextSecondary);
        mEmptyTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        mEmptyTv.setGravity(Gravity.CENTER);
        mEmptyTv.setPadding(dp(20), dp(40), dp(20), dp(40));
        mEmptyTv.setVisibility(View.GONE);
        root.addView(mEmptyTv);

        mListView = new ListView(this);
        mListView.setDivider(null);
        mListView.setDividerHeight(dp(8));
        mListView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mAdapter = new LibrariesAdapter();
        mListView.setAdapter(mAdapter);
        root.addView(mListView);

        setContentView(root);
    }

    private void refreshList() {
        mItems.clear();
        mItems.addAll(mRepo.getLibraries());
        mAdapter.notifyDataSetChanged();

        int totalEntries = 0;
        for (int i = 0; i < mItems.size(); i++) {
            totalEntries += mItems.get(i).entryCount;
        }
        mStatsTv.setText("المكتبات المثبتة: " + mItems.size() + " • إجمالي العبارات: " + totalEntries);
        mEmptyTv.setVisibility(mItems.isEmpty() ? View.VISIBLE : View.GONE);
        mListView.setVisibility(mItems.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void launchImportPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "*/*"});
        try {
            startActivityForResult(intent, REQ_IMPORT_LIB);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "لم يتم العثور على مستعرض ملفات على الجهاز", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == REQ_IMPORT_LIB && resultCode == Activity.RESULT_OK && data != null && data.getData() != null) {
            final Uri uri = data.getData();
            final String fileName = resolveFileName(uri);
            Toast.makeText(this, "⏳ جارٍ استيراد وفهرسة المكتبة...", Toast.LENGTH_SHORT).show();
            mExecutor.execute(new Runnable() {
                @Override
                public void run() {
                    try {
                        String raw = readUriUtf8(uri);
                        final LibraryImportResult res = mRepo.importLibraryJson(raw, fileName);
                        mMainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing()) return;
                                refreshList();
                                if (!res.success) {
                                    new AlertDialog.Builder(LinguaKeyOfflineLibrariesActivity.this)
                                            .setTitle("فشل استيراد المكتبة")
                                            .setMessage(res.errorMessage)
                                            .setPositiveButton("حسناً", null)
                                            .show();
                                    return;
                                }
                                String arrow = res.meta.bidirectional ? " ⇄ " : " → ";
                                String pair = res.meta.sourceLang.toUpperCase(Locale.US)
                                        + arrow + res.meta.targetLang.toUpperCase(Locale.US);
                                new AlertDialog.Builder(LinguaKeyOfflineLibrariesActivity.this)
                                        .setTitle("تم استيراد المكتبة بنجاح ✓")
                                        .setMessage("• الاسم: " + res.meta.name
                                                + "\n• اللغات: " + pair
                                                + "\n• العبارات المستوردة: " + res.importedEntries
                                                + "\n• عناصر تم تجاهلها: " + res.skippedInvalid
                                                + "\n\nالمكتبة مفعّلة الآن وجاهزة للعمل بدون إنترنت.")
                                        .setPositiveButton("تم", null)
                                        .show();
                            }
                        });
                    } catch (final Throwable e) {
                        mMainHandler.post(new Runnable() {
                            @Override
                            public void run() {
                                if (isFinishing()) return;
                                Toast.makeText(LinguaKeyOfflineLibrariesActivity.this,
                                        "خطأ في قراءة الملف: " + e.getMessage(),
                                        Toast.LENGTH_LONG).show();
                            }
                        });
                    }
                }
            });
        }
    }

    private String resolveFileName(final Uri uri) {
        try (Cursor cursor = getContentResolver().query(uri, null, null, null, null)) {
            if (cursor != null && cursor.moveToFirst()) {
                int idx = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                if (idx >= 0) {
                    String n = cursor.getString(idx);
                    if (n != null) return n.replace(".json", "");
                }
            }
        } catch (Throwable ignored) {
        }
        return "مكتبة ترجمة";
    }

    private String readUriUtf8(final Uri uri) throws Exception {
        StringBuilder sb = new StringBuilder();
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            if (is == null) throw new java.io.IOException("تعذر فتح الملف");
            BufferedReader br = new BufferedReader(new InputStreamReader(is, StandardCharsets.UTF_8));
            char[] buf = new char[8192];
            int n;
            while ((n = br.read(buf)) != -1) {
                sb.append(buf, 0, n);
            }
        }
        return sb.toString();
    }

    private final class LibrariesAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mItems.size();
        }

        @Override
        public Object getItem(final int position) {
            return mItems.get(position);
        }

        @Override
        public long getItemId(final int position) {
            return position;
        }

        @Override
        public View getView(final int position, final View convertView, final ViewGroup parent) {
            final LibraryMeta item = mItems.get(position);

            LinearLayout card = new LinearLayout(LinguaKeyOfflineLibrariesActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            card.setPadding(dp(12), dp(10), dp(12), dp(10));
            card.setBackground(makeRounded(mCardColor, mStrokeColor, dp(12)));

            LinearLayout top = new LinearLayout(LinguaKeyOfflineLibrariesActivity.this);
            top.setOrientation(LinearLayout.HORIZONTAL);
            top.setGravity(Gravity.CENTER_VERTICAL);

            TextView badge = new TextView(LinguaKeyOfflineLibrariesActivity.this);
            String arrow = item.bidirectional ? " ⇄ " : " → ";
            badge.setText(item.sourceLang.toUpperCase(Locale.US) + arrow + item.targetLang.toUpperCase(Locale.US));
            badge.setTextColor(Color.WHITE);
            badge.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            badge.setTypeface(Typeface.DEFAULT_BOLD);
            badge.setPadding(dp(8), dp(3), dp(8), dp(3));
            badge.setBackground(makeRounded(
                    item.enabled ? 0xFF1E5631 : 0xFF5F6368,
                    item.enabled ? 0xFF2E7D32 : 0xFF5F6368,
                    dp(8)
            ));
            top.addView(badge);

            TextView countTv = new TextView(LinguaKeyOfflineLibrariesActivity.this);
            countTv.setText("  " + item.entryCount + " عبارة");
            countTv.setTextColor(mTextSecondary);
            countTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            countTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            top.addView(countTv);

            final Switch sw = new Switch(LinguaKeyOfflineLibrariesActivity.this);
            sw.setChecked(item.enabled);
            sw.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    mRepo.setLibraryEnabled(item.id, sw.isChecked());
                    refreshList();
                }
            });
            top.addView(sw);
            card.addView(top);

            TextView nameTv = new TextView(LinguaKeyOfflineLibrariesActivity.this);
            nameTv.setText(item.name);
            nameTv.setTextColor(item.enabled ? mTextPrimary : mTextSecondary);
            nameTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
            nameTv.setTypeface(Typeface.DEFAULT_BOLD);
            nameTv.setPadding(0, dp(6), 0, dp(2));
            card.addView(nameTv);

            if (item.description != null && !item.description.isEmpty()) {
                TextView descTv = new TextView(LinguaKeyOfflineLibrariesActivity.this);
                descTv.setText(item.description);
                descTv.setTextColor(mTextSecondary);
                descTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
                card.addView(descTv);
            }

            LinearLayout bottom = new LinearLayout(LinguaKeyOfflineLibrariesActivity.this);
            bottom.setOrientation(LinearLayout.HORIZONTAL);
            bottom.setGravity(Gravity.END);
            bottom.setPadding(0, dp(6), 0, 0);

            TextView delBtn = new TextView(LinguaKeyOfflineLibrariesActivity.this);
            delBtn.setText("حذف المكتبة");
            delBtn.setTextColor(0xFFEA4335);
            delBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
            delBtn.setTypeface(Typeface.DEFAULT_BOLD);
            delBtn.setPadding(dp(8), dp(4), dp(4), dp(4));
            delBtn.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    new AlertDialog.Builder(LinguaKeyOfflineLibrariesActivity.this)
                            .setTitle("حذف المكتبة")
                            .setMessage("هل تريد حذف المكتبة \"" + item.name + "\"؟")
                            .setPositiveButton("حذف", new DialogInterface.OnClickListener() {
                                @Override
                                public void onClick(final DialogInterface dialog, final int which) {
                                    mRepo.deleteLibrary(item.id);
                                    refreshList();
                                }
                            })
                            .setNegativeButton("إلغاء", null)
                            .show();
                }
            });
            bottom.addView(delBtn);
            card.addView(bottom);

            return card;
        }
    }

    private GradientDrawable makeRounded(final int fill, final int stroke, final int r) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(r);
        d.setColor(fill);
        d.setStroke(Math.max(1, dp(1)), stroke);
        return d;
    }

    private int dp(final float v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
