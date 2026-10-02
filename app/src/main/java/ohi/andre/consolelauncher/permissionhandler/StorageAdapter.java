package ohi.andre.consolelauncher.permissionhandler;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;

import ohi.andre.consolelauncher.R;

public class StorageAdapter extends RecyclerView.Adapter<StorageAdapter.StorageViewHolder> {

    public static class StorageItem {
        public String name;
        public String path;

        public StorageItem(String name, String path) {
            this.name = name;
            this.path = path;
        }
    }

    public interface OnStorageClickListener {
        void onStorageClick(StorageItem item);
    }

    private final Context context;
    private final List<StorageItem> items = new ArrayList<>();
    private final OnStorageClickListener listener;

    public StorageAdapter(Context context, OnStorageClickListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setItems(List<StorageItem> newItems) {
        items.clear();
        items.addAll(newItems);
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public StorageViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_storage, parent, false);
        return new StorageViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull StorageViewHolder holder, int position) {
        StorageItem item = items.get(position);
        holder.tvName.setText(item.name);
        holder.tvPath.setText(item.path);

        holder.itemView.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            listener.onStorageClick(items.get(pos));
        });
    }

    @Override
    public int getItemCount() {
        return items.size();
    }

    static class StorageViewHolder extends RecyclerView.ViewHolder {
        TextView tvName;
        TextView tvPath;

        StorageViewHolder(@NonNull View itemView) {
            super(itemView);
            tvName = itemView.findViewById(R.id.tv_storage_name);
            tvPath = itemView.findViewById(R.id.tv_storage_path);
        }
    }
}