package com.linguakey.keyboard.latin.settings;

import android.app.ActionBar;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.ActivityNotFoundException;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.InputType;
import android.text.TextUtils;
import android.text.TextWatcher;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.widget.ArrayAdapter;
import android.widget.BaseAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.EditText;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.RadioButton;
import android.widget.RadioGroup;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import com.linguakey.keyboard.R;
import com.linguakey.keyboard.latin.shortcuts.LinguaKeyShortcutModels.DuplicateStrategy;
import com.linguakey.keyboard.latin.shortcuts.LinguaKeyShortcutModels.ImportPreviewResult;
import com.linguakey.keyboard.latin.shortcuts.LinguaKeyShortcutModels.ImportReport;
import com.linguakey.keyboard.latin.shortcuts.LinguaKeyShortcutModels.ShortcutEntry;
import com.linguakey.keyboard.latin.shortcuts.LinguaKeyShortcutRepository;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Professional Arabic Personal Dictionaries & Shortcuts Manager (v4.6.0):
 * - Multi-select mode for batch-deleting multiple shortcuts at once.
 * - Delete single group (long-press chip) OR multi-select & batch-delete multiple groups at once.
 * - Full CRUD, instant search, JSON Import (with preview + conflict resolution) & JSON Export.
 * - Unified Material You theme matching SettingsActivity with Android 15 edge-to-edge safe insets.
 */
public final class LinguaKeyShortcutsActivity extends Activity {
    private static final int REQ_IMPORT_JSON = 54101;
    private static final int REQ_EXPORT_JSON = 54102;

    private static final String[] LANG_CODES = new String[]{"all", "ar", "en", "tr", "de", "fr", "es"};
    private static final String[] LANG_LABELS = new String[]{
            "كل اللغات (all)",
            "العربية (ar)",
            "الإنجليزية (en)",
            "التركية (tr)",
            "الألمانية (de)",
            "الفرنسية (fr)",
            "الإسبانية (es)"
    };

    private LinguaKeyShortcutRepository mRepository;
    private final ExecutorService mExecutor = Executors.newSingleThreadExecutor();
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());

    private boolean mIsDark;
    private int mBgColor;
    private int mCardColor;
    private int mStrokeColor;
    private int mTextPrimary;
    private int mTextSecondary;
    private int mAccentColor;

    private TextView mHeaderCountView;
    private LinearLayout mMultiSelectBar;
    private TextView mSelectionCountTv;
    private Button mDeleteSelectedBtn;
    private EditText mSearchInput;
    private LinearLayout mGroupChipsRow;
    private ListView mListView;
    private TextView mEmptyStateView;
    private ShortcutsAdapter mAdapter;

    private String mSelectedGroup = "الكل";
    private String mSearchQuery = "";
    private String mPendingExportGroup = null;
    private boolean mMultiSelectMode = false;
    private final Set<String> mSelectedIds = new HashSet<>();
    private final List<ShortcutEntry> mDisplayedItems = new ArrayList<>();

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        mRepository = LinguaKeyShortcutRepository.getInstance(this);
        resolveThemeColors();
        styleSystemBarsAndActionBar();
        buildUi();
        refreshAll();
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        if (item.getItemId() == android.R.id.home) {
            if (mMultiSelectMode) {
                exitMultiSelectMode();
            } else {
                finish();
            }
            return true;
        }
        return super.onOptionsItemSelected(item);
    }

    @Override
    public void onBackPressed() {
        if (mMultiSelectMode) {
            exitMultiSelectMode();
            return;
        }
        super.onBackPressed();
    }

    @Override
    protected void onDestroy() {
        mExecutor.shutdownNow();
        super.onDestroy();
    }

    private void resolveThemeColors() {
        int nightMode = getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK;
        mIsDark = (nightMode == Configuration.UI_MODE_NIGHT_YES);
        try {
            mBgColor = getResources().getColor(R.color.settings_bg);
            mCardColor = getResources().getColor(R.color.settings_card_bg);
            mStrokeColor = getResources().getColor(R.color.settings_card_stroke);
            mTextPrimary = getResources().getColor(R.color.settings_text_primary);
            mTextSecondary = getResources().getColor(R.color.settings_text_secondary);
            mAccentColor = getResources().getColor(R.color.settings_accent);
        } catch (Throwable ignored) {
            mBgColor = mIsDark ? 0xFF121316 : 0xFFF5F6FA;
            mCardColor = mIsDark ? 0xFF1E2026 : 0xFFFFFFFF;
            mStrokeColor = mIsDark ? 0xFF323640 : 0xFFDADCE0;
            mTextPrimary = mIsDark ? 0xFFE8EAED : 0xFF1F1F1F;
            mTextSecondary = mIsDark ? 0xFF9AA0A6 : 0xFF5F6368;
            mAccentColor = 0xFF0D6EFD;
        }
    }

    private void styleSystemBarsAndActionBar() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            Window window = getWindow();
            window.addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            window.setStatusBarColor(mBgColor);
            window.setNavigationBarColor(mBgColor);
        }
        ActionBar actionBar = getActionBar();
        if (actionBar != null) {
            actionBar.setTitle("القواميس والاختصارات");
            actionBar.setDisplayHomeAsUpEnabled(true);
            GradientDrawable abBg = new GradientDrawable();
            abBg.setColor(mBgColor);
            actionBar.setBackgroundDrawable(abBg);
            actionBar.setElevation(0);
        } else {
            setTitle("القواميس والاختصارات");
        }
    }

    private void buildUi() {
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(mBgColor);
        root.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        root.setFitsSystemWindows(true);

        final int basePadH = dp(14);
        final int basePadV = dp(10);
        root.setPadding(basePadH, basePadV, basePadH, basePadV);

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
                int safeTop = Math.max(basePadV, topInset + (getActionBar() != null ? dp(8) : 0));
                if (getActionBar() != null && topInset > 0 && topInset < actionBarH) {
                    safeTop = topInset + actionBarH + dp(8);
                }
                v.setPadding(basePadH, safeTop, basePadH, Math.max(basePadV, bottomInset + dp(8)));
                return insets;
            }
        });

        // 1. Stats + Quick Management Row ([Stats] [تحديد متعدد] [حذف مجموعات])
        LinearLayout statsRow = new LinearLayout(this);
        statsRow.setOrientation(LinearLayout.HORIZONTAL);
        statsRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams srLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        srLp.bottomMargin = dp(8);
        statsRow.setLayoutParams(srLp);

        mHeaderCountView = new TextView(this);
        mHeaderCountView.setTextColor(mTextSecondary);
        mHeaderCountView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        mHeaderCountView.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        statsRow.addView(mHeaderCountView);

        TextView multiBtn = makeSmallActionChip("تحديد للحذف", mCardColor, mAccentColor, mAccentColor);
        multiBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                if (mMultiSelectMode) exitMultiSelectMode();
                else enterMultiSelectMode(null);
            }
        });
        LinearLayout.LayoutParams mbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        mbLp.setMarginEnd(dp(6));
        multiBtn.setLayoutParams(mbLp);
        statsRow.addView(multiBtn);

        TextView delGroupsBtn = makeSmallActionChip("حذف مجموعات", mCardColor, 0xFFEA4335, 0xFFEA4335);
        delGroupsBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                showMultiDeleteGroupsDialog();
            }
        });
        statsRow.addView(delGroupsBtn);
        root.addView(statsRow);

        // 2. Contextual Multi-Select Action Bar (Hidden unless mMultiSelectMode == true)
        mMultiSelectBar = new LinearLayout(this);
        mMultiSelectBar.setOrientation(LinearLayout.HORIZONTAL);
        mMultiSelectBar.setGravity(Gravity.CENTER_VERTICAL);
        mMultiSelectBar.setPadding(dp(10), dp(6), dp(10), dp(6));
        mMultiSelectBar.setBackground(makeRoundedDrawable(0xFF2B1B1B, 0xFFEA4335, dp(12)));
        LinearLayout.LayoutParams msbLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        msbLp.bottomMargin = dp(8);
        mMultiSelectBar.setLayoutParams(msbLp);
        mMultiSelectBar.setVisibility(View.GONE);

        mSelectionCountTv = new TextView(this);
        mSelectionCountTv.setTextColor(Color.WHITE);
        mSelectionCountTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        mSelectionCountTv.setTypeface(Typeface.DEFAULT_BOLD);
        mSelectionCountTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
        mMultiSelectBar.addView(mSelectionCountTv);

        Button selectAllBtn = createStyledButton("الكل", 0xFF3C4043, Color.WHITE, 0xFF5F6368);
        selectAllBtn.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        selectAllBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                if (mSelectedIds.size() == mDisplayedItems.size()) {
                    mSelectedIds.clear();
                } else {
                    for (int i = 0; i < mDisplayedItems.size(); i++) {
                        mSelectedIds.add(mDisplayedItems.get(i).id);
                    }
                }
                updateMultiSelectUi();
            }
        });
        mMultiSelectBar.addView(selectAllBtn);

        mDeleteSelectedBtn = createStyledButton("حذف المحدد", 0xFFEA4335, Color.WHITE, 0xFFEA4335);
        LinearLayout.LayoutParams dsLp = new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34));
        dsLp.setMarginStart(dp(6));
        dsLp.setMarginEnd(dp(6));
        mDeleteSelectedBtn.setLayoutParams(dsLp);
        mDeleteSelectedBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                confirmDeleteSelectedShortcuts();
            }
        });
        mMultiSelectBar.addView(mDeleteSelectedBtn);

        Button cancelSelBtn = createStyledButton("إلغاء", 0xFF3C4043, Color.WHITE, 0xFF5F6368);
        cancelSelBtn.setLayoutParams(new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, dp(34)));
        cancelSelBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                exitMultiSelectMode();
            }
        });
        mMultiSelectBar.addView(cancelSelBtn);
        root.addView(mMultiSelectBar);

        // 3. Action Toolbar: [+ اختصار] [استيراد JSON] [تصدير JSON]
        LinearLayout actionRow = new LinearLayout(this);
        actionRow.setOrientation(LinearLayout.HORIZONTAL);
        actionRow.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams actionRowLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        actionRowLp.bottomMargin = dp(10);
        actionRow.setLayoutParams(actionRowLp);

        Button addBtn = createStyledButton("+ اختصار", mAccentColor, Color.WHITE, mAccentColor);
        addBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                showAddOrEditShortcutDialog(null);
            }
        });

        Button importBtn = createStyledButton("استيراد JSON", 0xFF1E5631, Color.WHITE, 0xFF2E7D32);
        importBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                launchImportJsonPicker();
            }
        });

        Button exportBtn = createStyledButton("تصدير JSON", mCardColor, mTextPrimary, mStrokeColor);
        exportBtn.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                showExportScopeDialog();
            }
        });

        LinearLayout.LayoutParams btnLp1 = new LinearLayout.LayoutParams(0, dp(42), 1f);
        btnLp1.setMarginEnd(dp(6));
        addBtn.setLayoutParams(btnLp1);

        LinearLayout.LayoutParams btnLp2 = new LinearLayout.LayoutParams(0, dp(42), 1.1f);
        btnLp2.setMarginEnd(dp(6));
        importBtn.setLayoutParams(btnLp2);

        LinearLayout.LayoutParams btnLp3 = new LinearLayout.LayoutParams(0, dp(42), 1.1f);
        exportBtn.setLayoutParams(btnLp3);

        actionRow.addView(addBtn);
        actionRow.addView(importBtn);
        actionRow.addView(exportBtn);
        root.addView(actionRow);

        // 4. Search Bar
        mSearchInput = new EditText(this);
        mSearchInput.setHint("🔍 ابحث في الاختصارات أو الجمل...");
        mSearchInput.setTextColor(mTextPrimary);
        mSearchInput.setHintTextColor(mTextSecondary);
        mSearchInput.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        mSearchInput.setSingleLine(true);
        mSearchInput.setPadding(dp(12), dp(10), dp(12), dp(10));
        mSearchInput.setBackground(makeRoundedDrawable(mCardColor, mStrokeColor, dp(12)));
        LinearLayout.LayoutParams searchLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        searchLp.bottomMargin = dp(10);
        mSearchInput.setLayoutParams(searchLp);
        mSearchInput.addTextChangedListener(new TextWatcher() {
            @Override
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override
            public void onTextChanged(CharSequence s, int start, int before, int count) {}
            @Override
            public void afterTextChanged(Editable s) {
                mSearchQuery = s != null ? s.toString() : "";
                refreshListOnly();
            }
        });
        root.addView(mSearchInput);

        // 5. Horizontal Group Filter Chips (Long-press any group to delete it!)
        HorizontalScrollView groupScroll = new HorizontalScrollView(this);
        groupScroll.setHorizontalScrollBarEnabled(false);
        LinearLayout.LayoutParams gsLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT);
        gsLp.bottomMargin = dp(10);
        groupScroll.setLayoutParams(gsLp);

        mGroupChipsRow = new LinearLayout(this);
        mGroupChipsRow.setOrientation(LinearLayout.HORIZONTAL);
        mGroupChipsRow.setGravity(Gravity.CENTER_VERTICAL);
        groupScroll.addView(mGroupChipsRow);
        root.addView(groupScroll);

        // 6. Empty State View
        mEmptyStateView = new TextView(this);
        mEmptyStateView.setText("لا توجد اختصارات في هذا العرض بعد.\nاضغط \"+ اختصار\" أو \"استيراد JSON\" لإضافة قواميسك.");
        mEmptyStateView.setTextColor(mTextSecondary);
        mEmptyStateView.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
        mEmptyStateView.setGravity(Gravity.CENTER);
        mEmptyStateView.setPadding(dp(20), dp(36), dp(20), dp(36));
        mEmptyStateView.setVisibility(View.GONE);
        root.addView(mEmptyStateView);

        // 7. Shortcuts ListView
        mListView = new ListView(this);
        mListView.setDivider(null);
        mListView.setDividerHeight(dp(8));
        mListView.setLayoutParams(new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));
        mAdapter = new ShortcutsAdapter();
        mListView.setAdapter(mAdapter);
        root.addView(mListView);

        setContentView(root);
    }

    private void enterMultiSelectMode(final String initialId) {
        mMultiSelectMode = true;
        mSelectedIds.clear();
        if (initialId != null) {
            mSelectedIds.add(initialId);
        }
        updateMultiSelectUi();
    }

    private void exitMultiSelectMode() {
        mMultiSelectMode = false;
        mSelectedIds.clear();
        updateMultiSelectUi();
    }

    private void updateMultiSelectUi() {
        mMultiSelectBar.setVisibility(mMultiSelectMode ? View.VISIBLE : View.GONE);
        int count = mSelectedIds.size();
        mSelectionCountTv.setText("المحدد: " + count);
        mDeleteSelectedBtn.setText("حذف (" + count + ")");
        mDeleteSelectedBtn.setEnabled(count > 0);
        mAdapter.notifyDataSetChanged();
    }

    private void confirmDeleteSelectedShortcuts() {
        final int count = mSelectedIds.size();
        if (count == 0) return;
        new AlertDialog.Builder(this)
                .setTitle("حذف الاختصارات المحددة")
                .setMessage("هل تريد حذف " + count + " اختصار بشكل نهائي؟")
                .setPositiveButton("حذف الكل", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int which) {
                        int removed = mRepository.deleteShortcuts(new HashSet<>(mSelectedIds));
                        exitMultiSelectMode();
                        refreshAll();
                        Toast.makeText(LinguaKeyShortcutsActivity.this,
                                "تم حذف " + removed + " اختصار ✓",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void showMultiDeleteGroupsDialog() {
        final List<String> groups = mRepository.getGroupNames();
        if (groups.isEmpty()) {
            Toast.makeText(this, "لا توجد مجموعات للحذف", Toast.LENGTH_SHORT).show();
            return;
        }
        final String[] labels = new String[groups.size()];
        final boolean[] checked = new boolean[groups.size()];
        for (int i = 0; i < groups.size(); i++) {
            String g = groups.get(i);
            int cnt = mRepository.getShortcutCountInGroup(g);
            labels[i] = g + " (" + cnt + " اختصار)";
        }

        new AlertDialog.Builder(this)
                .setTitle("تحديد وحذف المجموعات / القواميس")
                .setMultiChoiceItems(labels, checked, new DialogInterface.OnMultiChoiceClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int which, final boolean isChecked) {
                        checked[which] = isChecked;
                    }
                })
                .setPositiveButton("حذف المحدد مع اختصاراته", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int which) {
                        Set<String> toDelete = new HashSet<>();
                        for (int i = 0; i < groups.size(); i++) {
                            if (checked[i]) toDelete.add(groups.get(i));
                        }
                        if (toDelete.isEmpty()) {
                            Toast.makeText(LinguaKeyShortcutsActivity.this, "لم يتم تحديد أي مجموعة", Toast.LENGTH_SHORT).show();
                            return;
                        }
                        confirmDeleteGroups(toDelete);
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void confirmDeleteGroups(final Set<String> groupsToDelete) {
        int totalShortcuts = 0;
        for (String g : groupsToDelete) {
            totalShortcuts += mRepository.getShortcutCountInGroup(g);
        }
        new AlertDialog.Builder(this)
                .setTitle("تأكيد حذف المجموعات")
                .setMessage("سيتم حذف " + groupsToDelete.size() + " مجموعة مع " + totalShortcuts + " اختصار بداخلها. هل أنت متأكد؟")
                .setPositiveButton("تأكيد الحذف", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int which) {
                        mRepository.deleteGroups(groupsToDelete, true);
                        if (groupsToDelete.contains(mSelectedGroup)) {
                            mSelectedGroup = "الكل";
                        }
                        exitMultiSelectMode();
                        refreshAll();
                        Toast.makeText(LinguaKeyShortcutsActivity.this,
                                "تم حذف المجموعات المحددة بنجاح ✓",
                                Toast.LENGTH_SHORT).show();
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void refreshAll() {
        rebuildGroupChips();
        refreshListOnly();
    }

    private void rebuildGroupChips() {
        mGroupChipsRow.removeAllViews();
        List<String> groups = new ArrayList<>();
        groups.add("الكل");
        groups.addAll(mRepository.getGroupNames());

        for (int i = 0; i < groups.size(); i++) {
            final String group = groups.get(i);
            boolean selected = group.equals(mSelectedGroup);
            TextView chip = new TextView(this);
            chip.setText(group);
            chip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
            chip.setTypeface(selected ? Typeface.DEFAULT_BOLD : Typeface.DEFAULT);
            chip.setTextColor(selected ? Color.WHITE : mTextPrimary);
            chip.setPadding(dp(12), dp(6), dp(12), dp(6));
            chip.setBackground(makeRoundedDrawable(
                    selected ? mAccentColor : mCardColor,
                    selected ? mAccentColor : mStrokeColor,
                    dp(16)
            ));
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT);
            lp.setMarginEnd(dp(6));
            chip.setLayoutParams(lp);
            chip.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(final View v) {
                    mSelectedGroup = group;
                    refreshAll();
                }
            });
            if (!"الكل".equals(group)) {
                chip.setOnLongClickListener(new View.OnLongClickListener() {
                    @Override
                    public boolean onLongClick(final View v) {
                        Set<String> single = new HashSet<>();
                        single.add(group);
                        confirmDeleteGroups(single);
                        return true;
                    }
                });
            }
            mGroupChipsRow.addView(chip);
        }

        TextView addGroupChip = new TextView(this);
        addGroupChip.setText("+ مجموعة");
        addGroupChip.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
        addGroupChip.setTypeface(Typeface.DEFAULT_BOLD);
        addGroupChip.setTextColor(mAccentColor);
        addGroupChip.setPadding(dp(12), dp(6), dp(12), dp(6));
        addGroupChip.setBackground(makeRoundedDrawable(mCardColor, mAccentColor, dp(16)));
        addGroupChip.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(final View v) {
                showAddGroupDialog();
            }
        });
        mGroupChipsRow.addView(addGroupChip);
    }

    private void refreshListOnly() {
        List<ShortcutEntry> filtered = mRepository.searchShortcuts(mSearchQuery, mSelectedGroup);
        mDisplayedItems.clear();
        mDisplayedItems.addAll(filtered);
        mAdapter.notifyDataSetChanged();

        int total = mRepository.getTotalCount();
        mHeaderCountView.setText("الاختصارات: " + total + " • المعروض: " + mDisplayedItems.size());
        mEmptyStateView.setVisibility(mDisplayedItems.isEmpty() ? View.VISIBLE : View.GONE);
        mListView.setVisibility(mDisplayedItems.isEmpty() ? View.GONE : View.VISIBLE);
    }

    private void showAddGroupDialog() {
        final EditText input = new EditText(this);
        input.setHint("اسم المجموعة (مثال: العملاء، الفواتير)");
        input.setSingleLine(true);
        input.setPadding(dp(14), dp(10), dp(14), dp(10));

        new AlertDialog.Builder(this)
                .setTitle("إنشاء مجموعة قاموس جديدة")
                .setView(input)
                .setPositiveButton("إضافة", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int which) {
                        String name = input.getText().toString().trim();
                        if (!name.isEmpty()) {
                            mRepository.addGroup(name);
                            mSelectedGroup = name;
                            refreshAll();
                        }
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void showAddOrEditShortcutDialog(final ShortcutEntry existing) {
        final boolean isEdit = (existing != null);

        ScrollView scroll = new ScrollView(this);
        LinearLayout form = new LinearLayout(this);
        form.setOrientation(LinearLayout.VERTICAL);
        form.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        form.setPadding(dp(16), dp(12), dp(16), dp(12));
        scroll.addView(form);

        form.addView(makeFormLabel("الاختصار (الكلمة التي تكتبها):"));
        final EditText trigInput = new EditText(this);
        trigInput.setHint("مثال: addr أو thanks أو مرحبا");
        trigInput.setSingleLine(true);
        if (isEdit) trigInput.setText(existing.trigger);
        form.addView(trigInput);

        form.addView(makeFormLabel("النص الكامل أو الجملة الطويلة (يدعم الأسطر المتعددة والإيموجي):"));
        final EditText replInput = new EditText(this);
        replInput.setHint("اكتب الجملة الكاملة هنا بدون حد للطول...");
        replInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_MULTI_LINE);
        replInput.setMinLines(4);
        replInput.setMaxLines(10);
        replInput.setGravity(Gravity.TOP | Gravity.START);
        if (isEdit) replInput.setText(existing.replacement);
        form.addView(replInput);

        form.addView(makeFormLabel("المجموعة:"));
        final List<String> groups = mRepository.getGroupNames();
        if (groups.isEmpty()) groups.add("عام");
        if (isEdit && !groups.contains(existing.groupName)) {
            groups.add(existing.groupName);
        }
        final Spinner groupSpinner = new Spinner(this);
        ArrayAdapter<String> grpAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, groups);
        groupSpinner.setAdapter(grpAdapter);
        String defaultGrp = isEdit ? existing.groupName : ("الكل".equals(mSelectedGroup) ? groups.get(0) : mSelectedGroup);
        int grpIdx = groups.indexOf(defaultGrp);
        if (grpIdx >= 0) groupSpinner.setSelection(grpIdx);
        form.addView(groupSpinner);

        form.addView(makeFormLabel("اللغة:"));
        final Spinner langSpinner = new Spinner(this);
        ArrayAdapter<String> langAdapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, LANG_LABELS);
        langSpinner.setAdapter(langAdapter);
        int langIdx = 0;
        if (isEdit && existing.language != null) {
            for (int i = 0; i < LANG_CODES.length; i++) {
                if (LANG_CODES[i].equalsIgnoreCase(existing.language)) {
                    langIdx = i;
                    break;
                }
            }
        }
        langSpinner.setSelection(langIdx);
        form.addView(langSpinner);

        final Switch enabledSwitch = new Switch(this);
        enabledSwitch.setText("تفعيل الاختصار");
        enabledSwitch.setChecked(!isEdit || existing.enabled);
        enabledSwitch.setPadding(0, dp(10), 0, dp(4));
        form.addView(enabledSwitch);

        final AlertDialog dialog = new AlertDialog.Builder(this)
                .setTitle(isEdit ? "تعديل الاختصار" : "إضافة اختصار جديد")
                .setView(scroll)
                .setPositiveButton("حفظ", null)
                .setNegativeButton("إلغاء", null)
                .create();

        dialog.setOnShowListener(new DialogInterface.OnShowListener() {
            @Override
            public void onShow(final DialogInterface d) {
                Button saveBtn = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                saveBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(final View v) {
                        String trig = trigInput.getText().toString().trim();
                        String repl = replInput.getText().toString();
                        if (trig.isEmpty()) {
                            trigInput.setError("يرجى إدخال كلمة الاختصار");
                            return;
                        }
                        if (trig.contains(" ") || trig.contains("\n")) {
                            trigInput.setError("الاختصار يجب أن يكون كلمة واحدة بدون مسافات");
                            return;
                        }
                        if (repl.trim().isEmpty()) {
                            replInput.setError("يرجى إدخال النص الكامل المرتبط بالاختصار");
                            return;
                        }
                        String grp = (String) groupSpinner.getSelectedItem();
                        String lang = LANG_CODES[Math.max(0, langSpinner.getSelectedItemPosition())];
                        boolean enabled = enabledSwitch.isChecked();

                        ShortcutEntry entry = isEdit ? existing.copy() : new ShortcutEntry();
                        entry.trigger = trig;
                        entry.replacement = repl;
                        entry.groupName = grp;
                        entry.language = lang;
                        entry.enabled = enabled;

                        mRepository.addOrUpdateShortcut(entry);
                        refreshAll();
                        Toast.makeText(LinguaKeyShortcutsActivity.this,
                                isEdit ? "تم تحديث الاختصار ✓" : "تم حفظ الاختصار بنجاح ✓",
                                Toast.LENGTH_SHORT).show();
                        dialog.dismiss();
                    }
                });
            }
        });
        dialog.show();
    }

    private void launchImportJsonPicker() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*");
        intent.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"application/json", "text/plain", "*/*"});
        try {
            startActivityForResult(intent, REQ_IMPORT_JSON);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "لم يتم العثور على مستعرض ملفات على الجهاز", Toast.LENGTH_LONG).show();
        }
    }

    private void showExportScopeDialog() {
        final List<String> options = new ArrayList<>();
        options.add("تصدير جميع القواميس (" + mRepository.getTotalCount() + " اختصار)");
        final List<String> groups = mRepository.getGroupNames();
        for (int i = 0; i < groups.size(); i++) {
            options.add("المجموعة: " + groups.get(i));
        }

        new AlertDialog.Builder(this)
                .setTitle("تصدير القواميس إلى ملف JSON")
                .setItems(options.toArray(new String[0]), new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int which) {
                        mPendingExportGroup = (which == 0) ? null : groups.get(which - 1);
                        launchExportJsonPicker();
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void launchExportJsonPicker() {
        Intent intent = new Intent(Intent.ACTION_CREATE_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("application/json");
        String fileName = (mPendingExportGroup == null)
                ? "linguakey_dictionaries.json"
                : ("linguakey_" + mPendingExportGroup.replaceAll("\\s+", "_") + ".json");
        intent.putExtra(Intent.EXTRA_TITLE, fileName);
        try {
            startActivityForResult(intent, REQ_EXPORT_JSON);
        } catch (ActivityNotFoundException e) {
            Toast.makeText(this, "لم يتم العثور على مستعرض ملفات على الجهاز", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) {
            return;
        }
        final Uri uri = data.getData();
        if (requestCode == REQ_IMPORT_JSON) {
            startBackgroundJsonImportPreview(uri);
        } else if (requestCode == REQ_EXPORT_JSON) {
            startBackgroundJsonExport(uri, mPendingExportGroup);
        }
    }

    private void startBackgroundJsonImportPreview(final Uri uri) {
        Toast.makeText(this, "⏳ جارٍ قراءة وتحليل ملف JSON...", Toast.LENGTH_SHORT).show();
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String rawJson = readUriUtf8(uri);
                    final ImportPreviewResult preview = mRepository.parseAndPreviewJson(rawJson);
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            showPreImportSummaryDialog(preview);
                        }
                    });
                } catch (final Throwable e) {
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            if (isFinishing()) return;
                            new AlertDialog.Builder(LinguaKeyShortcutsActivity.this)
                                    .setTitle("فشل قراءة الملف")
                                    .setMessage("تعذر قراءة ملف JSON المحدد:\n" + e.getMessage())
                                    .setPositiveButton("حسناً", null)
                                    .show();
                        }
                    });
                }
            }
        });
    }

    private void showPreImportSummaryDialog(final ImportPreviewResult preview) {
        if (preview.hasFatalError()) {
            new AlertDialog.Builder(this)
                    .setTitle("ملف JSON غير صالح")
                    .setMessage(preview.fatalError + "\n\nلم يتم تعديل أو حذف أي بيانات موجودة.")
                    .setPositiveButton("حسناً", null)
                    .show();
            return;
        }

        if (preview.getTotalValidCount() == 0) {
            new AlertDialog.Builder(this)
                    .setTitle("لا توجد اختصارات صالحة")
                    .setMessage("لم يتم العثور على اختصارات صالحة للاستيراد.\nعدد العناصر غير الصالحة: " + preview.invalidCount)
                    .setPositiveButton("حسناً", null)
                    .show();
            return;
        }

        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
        box.setPadding(dp(18), dp(12), dp(18), dp(8));

        StringBuilder summary = new StringBuilder();
        summary.append("إصدار البنية (Schema): v").append(preview.schemaVersion).append("\n");
        summary.append("• اختصارات جديدة: ").append(preview.validNewEntries.size()).append("\n");
        summary.append("• اختصارات مكررة: ").append(preview.duplicateEntries.size()).append("\n");
        summary.append("• عناصر غير صالحة / أخطاء: ").append(preview.invalidCount).append("\n");
        summary.append("• المجموعات: ").append(preview.parsedGroupNames.size());

        TextView summaryTv = new TextView(this);
        summaryTv.setText(summary.toString());
        summaryTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f);
        summaryTv.setPadding(0, 0, 0, dp(10));
        box.addView(summaryTv);

        final RadioGroup strategyGroup = new RadioGroup(this);
        strategyGroup.setOrientation(RadioGroup.VERTICAL);
        final RadioButton rbSkip = new RadioButton(this);
        rbSkip.setText("تخطي الاختصار الموجود (الاحتفاظ بالقديم)");
        rbSkip.setChecked(true);
        final RadioButton rbOverwrite = new RadioButton(this);
        rbOverwrite.setText("استبدال الاختصار الموجود بالجديد");
        final RadioButton rbKeepBoth = new RadioButton(this);
        rbKeepBoth.setText("الاحتفاظ بالنسختين معاً (تظهر كلتاهما كاقتراحات)");

        strategyGroup.addView(rbSkip);
        strategyGroup.addView(rbOverwrite);
        strategyGroup.addView(rbKeepBoth);

        if (!preview.duplicateEntries.isEmpty()) {
            TextView dupTitle = new TextView(this);
            dupTitle.setText("عند وجود اختصار مكرر:");
            dupTitle.setTypeface(Typeface.DEFAULT_BOLD);
            dupTitle.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            dupTitle.setPadding(0, dp(4), 0, dp(6));
            box.addView(dupTitle);
            box.addView(strategyGroup);
        }

        new AlertDialog.Builder(this)
                .setTitle("ملخص الاستيراد قبل التأكيد")
                .setView(box)
                .setPositiveButton("تأكيد الاستيراد", new DialogInterface.OnClickListener() {
                    @Override
                    public void onClick(final DialogInterface dialog, final int which) {
                        final DuplicateStrategy strategy;
                        if (rbOverwrite.isChecked()) {
                            strategy = DuplicateStrategy.OVERWRITE;
                        } else if (rbKeepBoth.isChecked()) {
                            strategy = DuplicateStrategy.KEEP_BOTH;
                        } else {
                            strategy = DuplicateStrategy.SKIP;
                        }
                        commitImportInBackground(preview, strategy);
                    }
                })
                .setNegativeButton("إلغاء", null)
                .show();
    }

    private void commitImportInBackground(final ImportPreviewResult preview, final DuplicateStrategy strategy) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                final ImportReport report = mRepository.commitImport(preview, strategy);
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        if (isFinishing()) return;
                        refreshAll();
                        if (!report.success) {
                            new AlertDialog.Builder(LinguaKeyShortcutsActivity.this)
                                    .setTitle("تعذر إتمام الاستيراد")
                                    .setMessage(report.errorMessage)
                                    .setPositiveButton("حسناً", null)
                                    .show();
                            return;
                        }
                        String msg = "تم إتمام الاستيراد بنجاح ✓\n\n"
                                + "• تمت إضافة: " + report.addedCount + "\n"
                                + "• تم تحديث: " + report.updatedCount + "\n"
                                + "• تم تخطي: " + report.skippedCount + "\n"
                                + "• أخطاء تم تجاهلها: " + report.errorCount;
                        new AlertDialog.Builder(LinguaKeyShortcutsActivity.this)
                                .setTitle("تقرير الاستيراد")
                                .setMessage(msg)
                                .setPositiveButton("تم", null)
                                .show();
                    }
                });
            }
        });
    }

    private void startBackgroundJsonExport(final Uri uri, final String groupFilter) {
        mExecutor.execute(new Runnable() {
            @Override
            public void run() {
                try {
                    String json = mRepository.exportToJson(groupFilter);
                    try (OutputStream os = getContentResolver().openOutputStream(uri)) {
                        if (os == null) throw new java.io.IOException("تعذر فتح مسار الحفظ");
                        os.write(json.getBytes(StandardCharsets.UTF_8));
                        os.flush();
                    }
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(LinguaKeyShortcutsActivity.this,
                                    "تم تصدير القواميس بصيغة JSON بنجاح ✓",
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                } catch (final Throwable e) {
                    mMainHandler.post(new Runnable() {
                        @Override
                        public void run() {
                            Toast.makeText(LinguaKeyShortcutsActivity.this,
                                    "فشل التصدير: " + e.getMessage(),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
                }
            }
        });
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

    private final class ShortcutsAdapter extends BaseAdapter {
        @Override
        public int getCount() {
            return mDisplayedItems.size();
        }

        @Override
        public Object getItem(final int position) {
            return mDisplayedItems.get(position);
        }

        @Override
        public long getItemId(final int position) {
            return position;
        }

        @Override
        public View getView(final int position, final View convertView, final ViewGroup parent) {
            final ShortcutEntry item = mDisplayedItems.get(position);
            final boolean isSelected = mSelectedIds.contains(item.id);

            LinearLayout card = new LinearLayout(LinguaKeyShortcutsActivity.this);
            card.setOrientation(LinearLayout.VERTICAL);
            card.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            card.setPadding(dp(12), dp(10), dp(12), dp(8));
            card.setBackground(makeRoundedDrawable(
                    mCardColor,
                    (mMultiSelectMode && isSelected) ? 0xFFEA4335 : mStrokeColor,
                    dp(14)
            ));

            card.setOnLongClickListener(new View.OnLongClickListener() {
                @Override
                public boolean onLongClick(final View v) {
                    if (!mMultiSelectMode) {
                        enterMultiSelectMode(item.id);
                    }
                    return true;
                }
            });

            if (mMultiSelectMode) {
                card.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(final View v) {
                        if (mSelectedIds.contains(item.id)) mSelectedIds.remove(item.id);
                        else mSelectedIds.add(item.id);
                        updateMultiSelectUi();
                    }
                });
            }

            LinearLayout topRow = new LinearLayout(LinguaKeyShortcutsActivity.this);
            topRow.setOrientation(LinearLayout.HORIZONTAL);
            topRow.setGravity(Gravity.CENTER_VERTICAL);

            if (mMultiSelectMode) {
                final CheckBox cb = new CheckBox(LinguaKeyShortcutsActivity.this);
                cb.setChecked(isSelected);
                cb.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(final View v) {
                        if (cb.isChecked()) mSelectedIds.add(item.id);
                        else mSelectedIds.remove(item.id);
                        updateMultiSelectUi();
                    }
                });
                topRow.addView(cb);
            }

            TextView trigPill = new TextView(LinguaKeyShortcutsActivity.this);
            trigPill.setText("⚡ " + item.trigger);
            trigPill.setTextColor(Color.WHITE);
            trigPill.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
            trigPill.setTypeface(Typeface.DEFAULT_BOLD);
            trigPill.setPadding(dp(10), dp(4), dp(10), dp(4));
            trigPill.setBackground(makeRoundedDrawable(
                    item.enabled ? mAccentColor : 0xFF5F6368,
                    item.enabled ? mAccentColor : 0xFF5F6368,
                    dp(10)
            ));
            topRow.addView(trigPill);

            TextView metaTv = new TextView(LinguaKeyShortcutsActivity.this);
            metaTv.setText("  " + item.groupName + " • " + item.language.toUpperCase(Locale.US));
            metaTv.setTextColor(mTextSecondary);
            metaTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
            metaTv.setLayoutParams(new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));
            topRow.addView(metaTv);

            if (!mMultiSelectMode) {
                final Switch toggle = new Switch(LinguaKeyShortcutsActivity.this);
                toggle.setChecked(item.enabled);
                toggle.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(final View v) {
                        mRepository.setShortcutEnabled(item.id, toggle.isChecked());
                        refreshListOnly();
                    }
                });
                topRow.addView(toggle);
            }
            card.addView(topRow);

            TextView replTv = new TextView(LinguaKeyShortcutsActivity.this);
            replTv.setText(item.replacement);
            replTv.setTextColor(item.enabled ? mTextPrimary : mTextSecondary);
            replTv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13.5f);
            replTv.setMaxLines(3);
            replTv.setEllipsize(TextUtils.TruncateAt.END);
            replTv.setPadding(dp(2), dp(6), dp(2), dp(6));
            card.addView(replTv);

            if (!mMultiSelectMode) {
                LinearLayout bottomRow = new LinearLayout(LinguaKeyShortcutsActivity.this);
                bottomRow.setOrientation(LinearLayout.HORIZONTAL);
                bottomRow.setGravity(Gravity.END);

                TextView editBtn = new TextView(LinguaKeyShortcutsActivity.this);
                editBtn.setText("تعديل");
                editBtn.setTextColor(mAccentColor);
                editBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                editBtn.setTypeface(Typeface.DEFAULT_BOLD);
                editBtn.setPadding(dp(12), dp(4), dp(12), dp(4));
                editBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(final View v) {
                        showAddOrEditShortcutDialog(item);
                    }
                });

                TextView delBtn = new TextView(LinguaKeyShortcutsActivity.this);
                delBtn.setText("حذف");
                delBtn.setTextColor(0xFFEA4335);
                delBtn.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f);
                delBtn.setTypeface(Typeface.DEFAULT_BOLD);
                delBtn.setPadding(dp(12), dp(4), dp(4), dp(4));
                delBtn.setOnClickListener(new View.OnClickListener() {
                    @Override
                    public void onClick(final View v) {
                        new AlertDialog.Builder(LinguaKeyShortcutsActivity.this)
                                .setTitle("حذف الاختصار")
                                .setMessage("هل تريد حذف الاختصار \"" + item.trigger + "\"؟")
                                .setPositiveButton("حذف", new DialogInterface.OnClickListener() {
                                    @Override
                                    public void onClick(final DialogInterface dialog, final int which) {
                                        mRepository.deleteShortcut(item.id);
                                        refreshAll();
                                    }
                                })
                                .setNegativeButton("إلغاء", null)
                                .show();
                    }
                });

                bottomRow.addView(editBtn);
                bottomRow.addView(delBtn);
                card.addView(bottomRow);
            }

            return card;
        }
    }

    private TextView makeSmallActionChip(final String text, final int fill, final int textColor, final int stroke) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextColor(textColor);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11.5f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(dp(10), dp(4), dp(10), dp(4));
        tv.setBackground(makeRoundedDrawable(fill, stroke, dp(12)));
        return tv;
    }

    private TextView makeFormLabel(final String text) {
        TextView tv = new TextView(this);
        tv.setText(text);
        tv.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13f);
        tv.setTypeface(Typeface.DEFAULT_BOLD);
        tv.setPadding(0, dp(8), 0, dp(4));
        return tv;
    }

    private Button createStyledButton(final String text, final int fillColor, final int textColor, final int strokeColor) {
        Button b = new Button(this);
        b.setText(text);
        b.setAllCaps(false);
        b.setSingleLine(true);
        b.setEllipsize(TextUtils.TruncateAt.END);
        b.setGravity(Gravity.CENTER);
        b.setTextColor(textColor);
        b.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f);
        b.setTypeface(Typeface.DEFAULT_BOLD);
        b.setPadding(dp(8), 0, dp(8), 0);
        b.setBackground(makeRoundedDrawable(fillColor, strokeColor, dp(10)));
        return b;
    }

    private GradientDrawable makeRoundedDrawable(final int fillColor, final int strokeColor, final int radiusPx) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.RECTANGLE);
        d.setCornerRadius(radiusPx);
        d.setColor(fillColor);
        d.setStroke(Math.max(1, dp(1)), strokeColor);
        return d;
    }

    private int dp(final float v) {
        return (int) TypedValue.applyDimension(
                TypedValue.COMPLEX_UNIT_DIP, v, getResources().getDisplayMetrics());
    }
}
