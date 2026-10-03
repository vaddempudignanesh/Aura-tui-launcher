package ohi.andre.consolelauncher.alarm;

import android.content.Context;
import android.graphics.Color;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ohi.andre.consolelauncher.R;

public class AlarmAdapter extends RecyclerView.Adapter<AlarmAdapter.VH> {

    public interface OnEdit { void onEdit(AlarmModel m); }
    public interface OnToggle { void onToggle(AlarmModel m); }

    private final Context ctx;
    private final List<AlarmModel> items = new ArrayList<>();
    private final OnEdit onEdit;
    private final OnToggle onToggle;

    public AlarmAdapter(Context c, OnEdit e, OnToggle t) {
        this.ctx = c; this.onEdit = e; this.onToggle = t;
    }

    public void setItems(List<AlarmModel> list) {
        items.clear();
        items.addAll(list);
        notifyDataSetChanged();
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
        h.time.setText(m.formatted());
        h.label.setText(m.label == null || m.label.isEmpty()
                ? (m.repeats() ? "Repeats" : "Once")
                : m.label);

        h.toggle.setText(m.enabled ? "ON" : "OFF");
        h.toggle.setTextColor(m.enabled ? 0xFF00FF00 : 0xFFFF5555);

        h.row.setBackground(ctx.getDrawable(R.drawable.bg_card_alarm));
        h.row.setOnClickListener(v -> onEdit.onEdit(m));
        h.toggle.setOnClickListener(v -> onToggle.onToggle(m));
    }

    @Override
    public int getItemCount() { return items.size(); }

    static class VH extends RecyclerView.ViewHolder {
        LinearLayout row;
        TextView time, label, toggle;
        VH(View v) {
            super(v);
            row = v.findViewById(R.id.item_alarm_root);
            time = v.findViewById(R.id.item_alarm_time);
            label = v.findViewById(R.id.item_alarm_label);
            toggle = v.findViewById(R.id.item_alarm_toggle);
        }
    }
}