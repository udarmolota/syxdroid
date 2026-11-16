package com.udarmolota.syxdroid.fragments;

import android.net.Uri;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.ImageButton;
import android.widget.ImageView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.Fragment;
import androidx.recyclerview.widget.ItemTouchHelper;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.udarmolota.syxdroid.GameActivity;
import com.udarmolota.syxdroid.R;
import com.udarmolota.syxdroid.game.GameInstance;
import com.udarmolota.syxdroid.game.GameInstanceManager;
import com.udarmolota.syxdroid.game.SyxModImporter;
import com.udarmolota.syxdroid.game.SyxMods;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Mods of one instance, from the side menu (as in RimDroid). One list: "On" in load order — the
 * game's MODS list — then "Off" by name, with a search field. Each row has a switch and a delete
 * button; a tap shows the mod's details. Mods that are on are dragged by their handle (written when
 * the finger lifts), and a long press offers up / down / top / bottom. Songs of Syx mods carry no
 * load-order rules, so Sort puts the mods that are on in name order. While the game runs the list
 * is read-only: the game reads it at start and keeps its own copy.
 */
public class ModsFragment extends Fragment {

    private Spinner spInstance;
    private TextView tvRunning, tvEmpty;
    private EditText etSearch;
    private RecyclerView rv;
    private final List<GameInstance> instances = new ArrayList<>();
    private final List<Object> rows = new ArrayList<>();   // String = section header, Row = a mod
    private boolean editable;
    private View btnSort, btnEnableAll, btnDisableAll, btnCopy, btnInstall;
    private boolean dragged;
    private ItemTouchHelper dragHelper;
    private ActivityResultLauncher<String[]> pickZip;

    /** A row: the installed mod, or null for a listed mod whose folder is missing. */
    private static final class Row {
        final String id;
        @Nullable final SyxMods.Mod mod;
        final boolean active;
        Row(String id, @Nullable SyxMods.Mod mod, boolean active) { this.id = id; this.mod = mod; this.active = active; }
    }

    @Override
    public void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        pickZip = registerForActivityResult(new ActivityResultContracts.OpenDocument(), this::installZip);
    }

    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState) {
        return inflater.inflate(R.layout.fragment_mods, container, false);
    }

    @Override
    public void onViewCreated(@NonNull View v, @Nullable Bundle savedInstanceState) {
        super.onViewCreated(v, savedInstanceState);
        spInstance = v.findViewById(R.id.sp_mods_instance);
        tvRunning = v.findViewById(R.id.tv_mods_game_running);
        tvEmpty = v.findViewById(R.id.tv_mods_empty);
        etSearch = v.findViewById(R.id.et_mods_search);
        rv = v.findViewById(R.id.rv_mods);
        rv.setLayoutManager(new LinearLayoutManager(requireContext()));
        rv.setAdapter(adapter);
        dragHelper = new ItemTouchHelper(dragCallback);
        dragHelper.attachToRecyclerView(rv);

        instances.clear();
        instances.addAll(GameInstanceManager.requireSingleton().getInstances());
        List<String> names = new ArrayList<>();
        if (instances.isEmpty()) names.add(getString(R.string.no_instances));
        // Several instances: none is picked for the player (changing the wrong one's mods is too
        // easy); a single one is picked at once.
        if (instances.size() > 1) names.add(getString(R.string.mods_choose_instance));
        for (GameInstance gi : instances) names.add(gi.getName());
        ArrayAdapter<String> a = new ArrayAdapter<>(requireContext(), R.layout.spinner_item, names);
        a.setDropDownViewResource(R.layout.spinner_dropdown_item);
        spInstance.setAdapter(a);
        spInstance.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(AdapterView<?> p, View view, int pos, long id) { refresh(); }
            @Override public void onNothingSelected(AdapterView<?> p) {}
        });
        etSearch.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a1, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a1, int b, int c) {}
            @Override public void afterTextChanged(Editable s) { refresh(); }
        });
        btnInstall = v.findViewById(R.id.btn_mods_install);
        btnInstall.setOnClickListener(x -> pickZip.launch(new String[]{"application/zip", "application/octet-stream", "*/*"}));
        btnSort = v.findViewById(R.id.btn_mods_sort);
        btnSort.setOnClickListener(x -> sort());
        btnEnableAll = v.findViewById(R.id.btn_mods_enable_all);
        btnDisableAll = v.findViewById(R.id.btn_mods_disable_all);
        btnEnableAll.setOnClickListener(x -> enableAllShown());
        btnDisableAll.setOnClickListener(x -> disableAllShown());
        btnCopy = v.findViewById(R.id.btn_mods_copy);
        btnCopy.setOnClickListener(x -> chooseCopyTarget());
    }

    @Override
    public void onResume() {
        super.onResume();
        refresh();   // mods may have been installed or downloaded in the meantime
    }

    @Nullable
    private GameInstance current() {
        int i = spInstance == null ? -1 : spInstance.getSelectedItemPosition();
        if (instances.size() > 1) i--;   // the "choose an instance" line comes first
        return (i >= 0 && i < instances.size()) ? instances.get(i) : null;
    }

    private void refresh() {
        if (rv == null) return;
        rows.clear();
        GameInstance gi = current();
        boolean running = GameActivity.isGameRunning();
        tvRunning.setVisibility(running ? View.VISIBLE : View.GONE);
        editable = gi != null && !running;
        btnSort.setEnabled(editable);
        btnEnableAll.setEnabled(editable);
        btnDisableAll.setEnabled(editable);
        btnCopy.setEnabled(editable);
        btnInstall.setEnabled(editable);
        if (gi == null) {
            tvEmpty.setVisibility(instances.isEmpty() ? View.VISIBLE : View.GONE);
            adapter.notifyDataSetChanged();
            return;
        }
        List<SyxMods.Mod> mods = SyxMods.installed(gi);
        List<String> order = SyxMods.activeOrder(gi);
        String q = etSearch.getText().toString().trim().toLowerCase(Locale.ROOT);
        Map<String, SyxMods.Mod> byId = SyxMods.byId(mods);
        Set<String> activeIds = new HashSet<>();
        List<Row> active = new ArrayList<>(), inactive = new ArrayList<>();
        for (String id : order) {
            if (!activeIds.add(id)) continue;
            Row r = new Row(id, byId.get(id), true);
            if (matches(r, q)) active.add(r);
        }
        for (SyxMods.Mod m : mods) {
            if (activeIds.contains(m.id)) continue;
            Row r = new Row(m.id, m, false);
            if (matches(r, q)) inactive.add(r);
        }
        rows.add(getString(R.string.mods_active, active.size()));
        rows.addAll(active);
        rows.add(getString(R.string.mods_inactive, inactive.size()));
        rows.addAll(inactive);
        tvEmpty.setVisibility(mods.isEmpty() && order.isEmpty() ? View.VISIBLE : View.GONE);
        adapter.notifyDataSetChanged();
    }

    private static boolean matches(Row r, String q) {
        if (q.isEmpty()) return true;
        if (r.id.toLowerCase(Locale.ROOT).contains(q)) return true;
        return r.mod != null && SyxMods.searchText(r.mod).contains(q);
    }

    private void failed() {
        Toast.makeText(requireContext(), R.string.mods_switch_failed, Toast.LENGTH_SHORT).show();
    }

    private void toggle(Row r, boolean on) {
        GameInstance gi = current();
        if (gi == null) return;
        if (!SyxMods.setActive(gi, r.id, on)) failed();
        refresh();
    }

    // ---- install from a ZIP ----

    private void installZip(@Nullable Uri uri) {
        GameInstance gi = current();
        if (uri == null || gi == null) return;
        String zipName = displayName(uri);
        String fallback = zipName != null && zipName.toLowerCase(Locale.ROOT).endsWith(".zip")
                ? zipName.substring(0, zipName.length() - 4) : zipName;
        android.content.Context app = requireContext().getApplicationContext();
        androidx.appcompat.app.AlertDialog wait = new MaterialAlertDialogBuilder(requireContext())
                .setMessage(R.string.mods_installing)
                .setCancelable(false)
                .show();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            String message;
            try {
                SyxModImporter.Result r = SyxModImporter.install(app, gi, uri, fallback);
                message = app.getString(R.string.mods_installed, String.join(", ", r.installed));
            } catch (Exception e) {
                message = app.getString(R.string.mods_install_failed, String.valueOf(e.getMessage()));
            }
            String m = message;
            main.post(() -> {
                wait.dismiss();
                if (!isAdded()) { Toast.makeText(app, m, Toast.LENGTH_LONG).show(); return; }
                new MaterialAlertDialogBuilder(requireContext())
                        .setMessage(m)
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
                refresh();
            });
        }, "syx-mod-import").start();
    }

    @Nullable
    private String displayName(Uri uri) {
        try (android.database.Cursor c = requireContext().getContentResolver().query(uri,
                new String[]{android.provider.OpenableColumns.DISPLAY_NAME}, null, null, null)) {
            if (c != null && c.moveToFirst()) return c.getString(0);
        } catch (RuntimeException ignored) {
            // the file name is only a fallback for the mod's folder name
        }
        return null;
    }

    // ---- enable / disable everything shown ----

    private List<Row> shown(boolean active) {
        List<Row> out = new ArrayList<>();
        for (Object o : rows) if (o instanceof Row && ((Row) o).active == active) out.add((Row) o);
        return out;
    }

    /** Every mod shown that is off goes to the end of the load order. */
    private void enableAllShown() {
        GameInstance gi = current();
        if (gi == null || !editable) return;
        List<String> order = SyxMods.activeOrder(gi);
        List<Row> off = shown(false);
        if (off.isEmpty()) return;
        for (Row r : off) if (!order.contains(r.id)) order.add(r.id);
        if (!SyxMods.writeOrder(gi, order)) failed();
        else Toast.makeText(requireContext(), getString(R.string.mods_enabled_n, off.size()), Toast.LENGTH_LONG).show();
        refresh();
    }

    /** Every mod shown that is on is switched off; their load order is lost, so it asks first. */
    private void disableAllShown() {
        GameInstance gi = current();
        if (gi == null || !editable) return;
        List<String> ids = new ArrayList<>();
        for (Row r : shown(true)) ids.add(r.id);
        if (ids.isEmpty()) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setMessage(getString(R.string.mods_disable_all_confirm, ids.size()))
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mods_disable_all, (d, w) -> {
                    List<String> order = SyxMods.activeOrder(gi);
                    order.removeAll(ids);
                    if (!SyxMods.writeOrder(gi, order)) failed();
                    refresh();
                })
                .show();
    }

    // ---- copy the mods to another instance ----

    private void chooseCopyTarget() {
        GameInstance from = current();
        if (from == null) return;
        List<GameInstance> targets = new ArrayList<>();
        for (GameInstance gi : instances) if (!gi.getName().equals(from.getName())) targets.add(gi);
        if (targets.isEmpty()) {
            Toast.makeText(requireContext(), R.string.mods_copy_no_target, Toast.LENGTH_LONG).show();
            return;
        }
        String[] names = new String[targets.size()];
        for (int i = 0; i < names.length; i++) names[i] = targets.get(i).getName();
        final int[] picked = {0};
        View box = getLayoutInflater().inflate(R.layout.dialog_mods_copy, null);
        android.widget.ListView list = box.findViewById(R.id.lv_mods_copy_targets);
        list.setChoiceMode(android.widget.ListView.CHOICE_MODE_SINGLE);
        list.setAdapter(new ArrayAdapter<>(requireContext(), android.R.layout.simple_list_item_single_choice, names));
        list.setItemChecked(0, true);
        list.setOnItemClickListener((p, view, pos, id) -> picked[0] = pos);
        android.widget.CheckBox withOrder = box.findViewById(R.id.cb_mods_copy_order);
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.mods_copy_title, from.getName()))
                .setView(box)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mods_copy_next, (d, w) -> chooseModsToCopy(from, targets.get(picked[0]), withOrder.isChecked()))
                .show();
    }

    /** A tick for each mod: all ticked, except those the target already has. */
    private void chooseModsToCopy(GameInstance from, GameInstance to, boolean withOrder) {
        List<SyxMods.Mod> mods = SyxMods.installed(from);
        if (mods.isEmpty()) {
            Toast.makeText(requireContext(), R.string.mods_copy_nothing, Toast.LENGTH_LONG).show();
            return;
        }
        Set<String> there = SyxMods.byId(SyxMods.installed(to)).keySet();
        String[] labels = new String[mods.size()];
        boolean[] checked = new boolean[mods.size()];
        for (int i = 0; i < labels.length; i++) {
            boolean has = there.contains(mods.get(i).id);
            labels[i] = has ? getString(R.string.mods_copy_already_there, mods.get(i).name) : mods.get(i).name;
            checked[i] = !has;
        }
        androidx.appcompat.app.AlertDialog dialog = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.mods_copy_pick_title, to.getName()))
                .setMultiChoiceItems(labels, checked, (d, which, isChecked) -> {
                    checked[which] = isChecked;
                    updateSelectAllButton((androidx.appcompat.app.AlertDialog) d, checked);
                })
                .setNeutralButton(R.string.mods_copy_uncheck_all, null)
                .setPositiveButton(R.string.mods_copy_go, (d, w) -> {
                    List<SyxMods.Mod> picked = new ArrayList<>();
                    for (int i = 0; i < checked.length; i++) if (checked[i]) picked.add(mods.get(i));
                    if (picked.isEmpty() && !withOrder) {
                        Toast.makeText(requireContext(), R.string.mods_copy_none_picked, Toast.LENGTH_SHORT).show();
                        return;
                    }
                    copyMods(from, to, picked, withOrder);
                })
                .setNegativeButton(android.R.string.cancel, null)
                .show();
        updateSelectAllButton(dialog, checked);
        dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL).setOnClickListener(x -> {
            boolean all = true;
            for (boolean c : checked) all &= c;
            for (int i = 0; i < checked.length; i++) {
                checked[i] = !all;
                dialog.getListView().setItemChecked(i, !all);
            }
            updateSelectAllButton(dialog, checked);
        });
    }

    private void updateSelectAllButton(androidx.appcompat.app.AlertDialog dialog, boolean[] checked) {
        android.widget.Button b = dialog.getButton(android.content.DialogInterface.BUTTON_NEUTRAL);
        if (b == null) return;
        boolean all = true;
        for (boolean c : checked) all &= c;
        b.setText(all ? R.string.mods_copy_uncheck_all : R.string.mods_copy_check_all);
    }

    private void copyMods(GameInstance from, GameInstance to, List<SyxMods.Mod> picked, boolean withOrder) {
        if (GameActivity.isGameRunning()) {
            Toast.makeText(requireContext(), R.string.mods_game_running, Toast.LENGTH_LONG).show();
            return;
        }
        android.content.Context app = requireContext().getApplicationContext();
        androidx.appcompat.app.AlertDialog wait = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.mods_copy_running, to.getName()))
                .setMessage("…")
                .setCancelable(false)
                .show();
        android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
        new Thread(() -> {
            File dest = SyxMods.modsDir(to);
            int copied = 0, already = 0;
            List<String> failedNames = new ArrayList<>();
            for (int i = 0; i < picked.size(); i++) {
                SyxMods.Mod m = picked.get(i);
                int n = i + 1;
                main.post(() -> wait.setMessage(n + " / " + picked.size() + "\n" + m.name));
                File target = new File(dest, m.id);
                if (target.exists()) { already++; continue; }
                try {
                    copyTree(m.folder, target);
                    copied++;
                } catch (java.io.IOException e) {
                    failedNames.add(m.name);
                }
            }
            boolean orderOk = !withOrder || SyxMods.writeOrder(to, SyxMods.activeOrder(from));
            int c = copied, a = already;
            main.post(() -> {
                wait.dismiss();
                String summary = app.getString(R.string.mods_copy_done, c, a);
                if (!isAdded()) { Toast.makeText(app, summary, Toast.LENGTH_LONG).show(); return; }
                StringBuilder sb = new StringBuilder(summary);
                if (withOrder) sb.append("\n\n").append(getString(orderOk ? R.string.mods_copy_order_done : R.string.mods_copy_order_failed));
                if (!failedNames.isEmpty()) {
                    sb.append("\n\n").append(getString(R.string.mods_copy_failed));
                    for (String name : failedNames) sb.append("\n• ").append(name);
                }
                new MaterialAlertDialogBuilder(requireContext())
                        .setTitle(getString(R.string.mods_copy_running, to.getName()))
                        .setMessage(sb.toString())
                        .setPositiveButton(android.R.string.ok, null)
                        .show();
            });
        }, "syx-mods-copy").start();
    }

    private static void copyTree(File src, File dest) throws java.io.IOException {
        if (src.isDirectory()) {
            if (!dest.isDirectory() && !dest.mkdirs()) throw new java.io.IOException("Failed to create " + dest);
            File[] kids = src.listFiles();
            if (kids != null) for (File k : kids) copyTree(k, new File(dest, k.getName()));
        } else {
            java.nio.file.Files.copy(src.toPath(), dest.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING);
        }
    }

    // ---- load order ----

    /** Songs of Syx mods give no load-order rules: Sort puts the mods that are on in name order. */
    private void sort() {
        GameInstance gi = current();
        if (gi == null) return;
        List<String> order = SyxMods.activeOrder(gi);
        Map<String, SyxMods.Mod> byId = SyxMods.byId(SyxMods.installed(gi));
        List<String> sorted = new ArrayList<>(order);
        sorted.sort((x, y) -> nameOf(x, byId).compareToIgnoreCase(nameOf(y, byId)));
        int moved = 0;
        for (int i = 0; i < order.size(); i++) if (!order.get(i).equals(sorted.get(i))) moved++;
        MaterialAlertDialogBuilder b = new MaterialAlertDialogBuilder(requireContext())
                .setTitle(R.string.mods_sort_title)
                .setMessage(moved == 0 ? getString(R.string.mods_sort_nochange) : getString(R.string.mods_sort_moved, moved));
        if (moved > 0) {
            b.setNegativeButton(android.R.string.cancel, null)
             .setPositiveButton(R.string.mods_sort_apply, (d, w) -> {
                 if (!SyxMods.writeOrder(gi, sorted)) failed();
                 refresh();
             });
        } else {
            b.setPositiveButton(android.R.string.ok, null);
        }
        b.show();
    }

    private static String nameOf(String id, Map<String, SyxMods.Mod> byId) {
        SyxMods.Mod m = byId.get(id);
        return m != null ? m.name : id;
    }

    private boolean draggable(int pos) {
        if (pos < 0 || pos >= rows.size() || !(rows.get(pos) instanceof Row)) return false;
        // Only the whole list: with a search filter the rows on screen are not the whole order.
        return editable && ((Row) rows.get(pos)).active && etSearch.getText().length() == 0;
    }

    private final ItemTouchHelper.Callback dragCallback = new ItemTouchHelper.Callback() {
        @Override public boolean isLongPressDragEnabled() { return false; }   // the handle starts it
        @Override public boolean isItemViewSwipeEnabled() { return false; }

        @Override
        public int getMovementFlags(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder h) {
            return draggable(h.getAdapterPosition()) ? makeMovementFlags(ItemTouchHelper.UP | ItemTouchHelper.DOWN, 0) : 0;
        }

        @Override
        public boolean onMove(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder from, @NonNull RecyclerView.ViewHolder to) {
            int a = from.getAdapterPosition(), b = to.getAdapterPosition();
            if (!draggable(a) || !draggable(b)) return false;   // stays inside "On"
            rows.add(b, rows.remove(a));
            adapter.notifyItemMoved(a, b);
            dragged = true;
            return true;
        }

        @Override public void onSwiped(@NonNull RecyclerView.ViewHolder h, int direction) {}

        @Override
        public void clearView(@NonNull RecyclerView r, @NonNull RecyclerView.ViewHolder h) {
            super.clearView(r, h);
            if (!dragged) return;
            dragged = false;
            GameInstance gi = current();
            if (gi == null) return;
            List<String> order = new ArrayList<>();
            for (Object o : rows) if (o instanceof Row && ((Row) o).active) order.add(((Row) o).id);
            if (!SyxMods.writeOrder(gi, order)) failed();
            rv.post(ModsFragment.this::refresh);   // after the drop animation
        }
    };

    /** Long press on a mod that is on: up / down / to the top / to the bottom of the load order. */
    private void showMoveMenu(View anchor, Row r) {
        GameInstance gi = current();
        if (gi == null || !editable || !r.active) return;
        android.widget.PopupMenu pm = new android.widget.PopupMenu(requireContext(), anchor);
        pm.getMenu().add(0, 1, 0, R.string.mods_move_top);
        pm.getMenu().add(0, 2, 1, R.string.mods_move_up);
        pm.getMenu().add(0, 3, 2, R.string.mods_move_down);
        pm.getMenu().add(0, 4, 3, R.string.mods_move_bottom);
        pm.setOnMenuItemClickListener(item -> {
            List<String> order = SyxMods.activeOrder(gi);
            int i = order.indexOf(r.id);
            if (i < 0) return true;
            order.remove(i);
            int to;
            switch (item.getItemId()) {
                case 1: to = 0; break;
                case 2: to = Math.max(0, i - 1); break;
                case 3: to = Math.min(order.size(), i + 1); break;
                default: to = order.size(); break;
            }
            order.add(to, r.id);
            if (!SyxMods.writeOrder(gi, order)) failed();
            refresh();
            return true;
        });
        pm.show();
    }

    private void confirmDelete(Row r) {
        GameInstance gi = current();
        if (gi == null || r.mod == null) return;
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(getString(R.string.mod_delete_title, r.mod.name))
                .setMessage(R.string.mod_delete_message)
                .setNegativeButton(android.R.string.cancel, null)
                .setPositiveButton(R.string.mod_delete_confirm, (d, w) -> {
                    if (!SyxMods.delete(gi, r.mod)) failed();
                    refresh();
                })
                .show();
    }

    /** The game version a mod is for, the instance's own in green. */
    private CharSequence versionsLine(SyxMods.Mod m, int gameMajor) {
        android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder();
        for (int k = Math.max(0, m.gameVersions.size() - 3); k < m.gameVersions.size(); k++) {
            if (s.length() > 0) s.append(", ");
            int at = s.length();
            s.append("V").append(String.valueOf(m.gameVersions.get(k)));
            if (m.gameVersions.get(k) == gameMajor)
                s.setSpan(new android.text.style.ForegroundColorSpan(0xFF4CAF50), at, s.length(), 0);
        }
        return s;
    }

    private void showDetails(Row r) {
        GameInstance gi = current();
        StringBuilder sb = new StringBuilder();
        if (r.mod == null) {
            sb.append(getString(R.string.mods_not_installed));
        } else {
            SyxMods.Mod m = r.mod;
            int major = gi != null ? SyxMods.gameMajor(gi) : -1;
            if (!m.author.isEmpty()) sb.append(getString(R.string.mods_author, m.author)).append('\n');
            if (!m.version.isEmpty()) sb.append(getString(R.string.mods_mod_version, m.version)).append('\n');
            if (!m.gameVersions.isEmpty())
                sb.append(getString(R.string.mods_versions, versionsLine(m, major).toString())).append('\n');
            if (!m.supports(major)) sb.append(getString(R.string.mods_made_for_note, "V" + major)).append('\n');
            if (m.hasScripts) sb.append(getString(R.string.mods_has_scripts)).append('\n');
            if (m.workshopId > 0) sb.append(getString(R.string.mods_workshop_item, m.workshopId)).append('\n');
            sb.append(getString(R.string.mods_folder, m.folder.getName()));
            if (!m.description.isEmpty()) {
                String d = m.description.replaceAll("\\[/?[a-zA-Z]+[^\\]]*\\]", "").trim();
                if (d.length() > 600) d = d.substring(0, 600) + "…";
                sb.append("\n\n").append(d);
            }
        }
        new MaterialAlertDialogBuilder(requireContext())
                .setTitle(r.mod != null ? r.mod.name : r.id)
                .setMessage(sb.toString())
                .setPositiveButton(android.R.string.ok, null)
                .show();
    }

    private final RecyclerView.Adapter<RecyclerView.ViewHolder> adapter = new RecyclerView.Adapter<RecyclerView.ViewHolder>() {
        @NonNull @Override
        public RecyclerView.ViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int type) {
            View v = LayoutInflater.from(parent.getContext()).inflate(R.layout.item_mod, parent, false);
            return new RecyclerView.ViewHolder(v) {};
        }

        @Override
        public void onBindViewHolder(@NonNull RecyclerView.ViewHolder h, int pos) {
            View v = h.itemView;
            Object item = rows.get(pos);
            TextView header = v.findViewById(R.id.tv_mod_header);
            View row = v.findViewById(R.id.row_mod);
            if (item instanceof String) {
                header.setVisibility(View.VISIBLE);
                header.setText((String) item);
                row.setVisibility(View.GONE);
                return;
            }
            header.setVisibility(View.GONE);
            row.setVisibility(View.VISIBLE);
            Row r = (Row) item;
            TextView name = v.findViewById(R.id.tv_mod_name), sub = v.findViewById(R.id.tv_mod_sub);
            MaterialSwitch sw = v.findViewById(R.id.sw_mod);
            ImageButton del = v.findViewById(R.id.btn_mod_delete);
            GameInstance gi = current();
            int major = gi != null ? SyxMods.gameMajor(gi) : -1;

            if (r.mod == null) {
                name.setText(r.id);
                sub.setText(R.string.mods_not_installed);
            } else {
                name.setText(r.mod.name);
                android.text.SpannableStringBuilder s = new android.text.SpannableStringBuilder(versionsLine(r.mod, major));
                if (!r.mod.author.isEmpty()) s.append(s.length() > 0 ? " · " : "").append(r.mod.author);
                sub.setText(s);
            }
            sub.setVisibility(sub.getText().length() == 0 ? View.GONE : View.VISIBLE);

            sw.setOnCheckedChangeListener(null);
            sw.setChecked(r.active);
            sw.setEnabled(editable);
            sw.setOnCheckedChangeListener((b, on) -> toggle(r, on));

            del.setVisibility(r.mod != null ? View.VISIBLE : View.INVISIBLE);
            del.setEnabled(editable);
            del.setOnClickListener(x -> confirmDelete(r));
            ImageView drag = v.findViewById(R.id.iv_mod_drag);
            boolean canDrag = draggable(pos);
            drag.setVisibility(r.active ? View.VISIBLE : View.GONE);
            drag.setAlpha(canDrag ? 1f : 0.3f);
            drag.setOnTouchListener((x, e) -> {
                if (canDrag && e.getActionMasked() == android.view.MotionEvent.ACTION_DOWN) dragHelper.startDrag(h);
                return canDrag;
            });
            row.setOnClickListener(x -> showDetails(r));
            row.setOnLongClickListener(x -> { showMoveMenu(x, r); return true; });
            row.setAlpha(r.mod == null ? 0.6f : 1f);
        }

        @Override public int getItemCount() { return rows.size(); }
    };
}
