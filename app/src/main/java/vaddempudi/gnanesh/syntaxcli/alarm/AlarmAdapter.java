package vaddempudi.gnanesh.syntaxcli.alarm;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import vaddempudi.gnanesh.syntaxcli.R;

public class AlarmAdapter extends RecyclerView.Adapter<AlarmAdapter.VH> {

    public interface OnEdit { void onEdit(AlarmModel m); }
    public interface OnToggle { void onToggle(AlarmModel m); }
    public interface OnSelectionChanged { void onSelectionChanged(int count); }

    private final Context ctx;
    private final List<AlarmModel> items = new ArrayList<>();
    private final Set<Long> selectedIds = new HashSet<>();
    private final OnEdit onEdit;
    private final OnToggle onToggle;
    private final OnSelectionChanged onSelectionChanged;
    private boolean selectionMode = false;

    public AlarmAdapter(Context c, OnEdit e, OnToggle t, OnSelectionChanged s) {
        this.ctx = c;
        this.onEdit = e;
        this.onToggle = t;
        this.onSelectionChanged = s;
    }

    public void setItems(List<AlarmModel> list) {
        items.clear();
        items.addAll(list);
        selectedIds.clear();
        if (selectedIds.isEmpty()) selectionMode = false;
        notifyDataSetChanged();
    }

    public boolean isSelectionMode() { return selectionMode; }
    public Set<Long> getSelectedIds() { return selectedIds; }

    public void clearSelection() {
        selectionMode = false;
        selectedIds.clear();
        notifyDataSetChanged();
        onSelectionChanged.onSelectionChanged(0);
    }

    public void deleteSelected() {
        for (long id : new ArrayList<>(selectedIds)) {
            AlarmModel m = null;
            for (AlarmModel a : items) if (a.id == id) { m = a; break; }
            if (m != null) {
                AlarmReceiver.cancel(ctx, m);
                AlarmStore.delete(ctx, id);
            }
        }
        selectionMode = false;
        selectedIds.clear();
        // Sync the foreground service with the new state of the store.
        if (AlarmStore.hasActive(ctx)) AlarmService.start(ctx);
        else AlarmService.stop(ctx);
        onSelectionChanged.onSelectionChanged(0);
    }

    public void enableSelected(boolean enable) {
        for (long id : new ArrayList<>(selectedIds)) {
            for (AlarmModel a : items) {
                if (a.id == id) {
                    a.enabled = enable;
                    AlarmStore.update(ctx, a);
                    if (enable) AlarmReceiver.schedule(ctx, a);
                    else AlarmReceiver.cancel(ctx, a);
                    break;
                }
            }
        }
        // Sync the foreground service with the new state of the store.
        if (AlarmStore.hasActive(ctx)) AlarmService.start(ctx);
        else AlarmService.stop(ctx);
        notifyDataSetChanged();
    }

    private void toggleSelection(AlarmModel m) {
        if (!selectionMode) {
            selectionMode = true;
        }
        if (selectedIds.contains(m.id)) selectedIds.remove(m.id);
        else selectedIds.add(m.id);
        if (selectedIds.isEmpty()) selectionMode = false;
        notifyDataSetChanged();
        onSelectionChanged.onSelectionChanged(selectedIds.size());
    }

    @NonNull
    @Override
    public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View v = LayoutInflater.from(ctx).inflate(R.layout.item_alarm, parent, false);
        return new VH(v);
    }

    @Override
    public void onBindViewHolder(@NonNull VH h, int position) {
        AlarmModel m = items.get(position);
        boolean selected = selectedIds.contains(m.id);

        h.time.setText(m.formattedAmPm());
        String sub = m.repeatSummary();
        if (m.label != null && !m.label.isEmpty()) sub = m.label + "  •  " + sub;
        h.label.setText(sub);

        h.enable.setChecked(m.enabled);
        h.enable.setOnCheckedChangeListener(null);
        h.enable.setOnCheckedChangeListener((v, checked) -> {
            m.enabled = checked;
            AlarmStore.update(ctx, m);
            if (checked) {
                AlarmReceiver.schedule(ctx, m);
                AlarmService.start(ctx);
            } else {
                AlarmReceiver.cancel(ctx, m);
                if (!AlarmStore.hasActive(ctx)) {
                    AlarmService.stop(ctx);
                } else {
                    AlarmService.start(ctx); // refresh notification text
                }
            }
        });

        h.row.setBackground(ctx.getDrawable(selected
                ? R.drawable.bg_card_alarm_selected
                : R.drawable.bg_card_alarm));

        h.row.setOnClickListener(v -> {
            if (selectionMode) toggleSelection(m);
            else onEdit.onEdit(m);
        });

        h.row.setOnLongClickListener(v -> {
            toggleSelection(m);
            return true;
        });
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        LinearLayout row;
        TextView time, label;
        CheckBox enable;
        VH(View v) {
            super(v);
            row = v.findViewById(R.id.item_alarm_root);
            time = v.findViewById(R.id.item_alarm_time);
            label = v.findViewById(R.id.item_alarm_label);
            enable = v.findViewById(R.id.item_alarm_enable);
        }
    }
}