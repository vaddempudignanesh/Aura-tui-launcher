package ohi.andre.consolelauncher;

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

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

public class FileManagerAdapter extends RecyclerView.Adapter<FileManagerAdapter.FileViewHolder> {

    public interface OnFileClickListener {
        void onFileClick(File file, int position);
        void onFileLongClick(File file, int position);
    }

    private final Context context;
    private final List<File> files = new ArrayList<>();
    private final Set<File> selectedFiles = new HashSet<>();
    private final OnFileClickListener listener;
    private boolean selectionMode = false;

    public FileManagerAdapter(Context context, OnFileClickListener listener) {
        this.context = context;
        this.listener = listener;
    }

    public void setFiles(List<File> newFiles) {
        files.clear();
        files.addAll(newFiles);
        notifyDataSetChanged();
    }

    public List<File> getFiles() {
        return files;
    }

    public Set<File> getSelectedFiles() {
        return selectedFiles;
    }

    public boolean isSelectionMode() {
        return selectionMode;
    }

    public void setSelectionMode(boolean mode) {
        this.selectionMode = mode;
        if (!mode) {
            selectedFiles.clear();
        }
        notifyDataSetChanged();
    }

    public void toggleSelection(File file) {
        if (selectedFiles.contains(file)) {
            selectedFiles.remove(file);
        } else {
            selectedFiles.add(file);
        }
        if (selectedFiles.isEmpty()) {
            selectionMode = false;
        }
        notifyDataSetChanged();
    }

    public void selectAll() {
        selectedFiles.clear();
        selectedFiles.addAll(files);
        selectionMode = true;
        notifyDataSetChanged();
    }

    @NonNull
    @Override
    public FileViewHolder onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
        View view = LayoutInflater.from(context).inflate(R.layout.item_file, parent, false);
        return new FileViewHolder(view);
    }

    @Override
    public void onBindViewHolder(@NonNull FileViewHolder holder, int position) {
        File file = files.get(position);
        boolean isSelected = selectedFiles.contains(file);

        holder.tvName.setText(file.getName());

        if (file.isDirectory()) {
            holder.ivIcon.setImageResource(R.drawable.ic_folder);
            holder.tvSize.setText(getDirectorySizeText(file));
        } else {
            holder.ivIcon.setImageResource(R.drawable.ic_file);
            holder.tvSize.setText(formatSize(file.length()));
        }

        // Apply colors - pure black bg, green text
        if (isSelected) {
            holder.itemContainer.setBackgroundColor(Color.parseColor("#FF003300"));
            holder.tvName.setTextColor(Color.parseColor("#FF00FF00"));
            holder.tvSize.setTextColor(Color.parseColor("#FF00FF00"));
        } else {
            holder.itemContainer.setBackgroundColor(Color.parseColor("#FF000000"));
            holder.tvName.setTextColor(Color.parseColor("#FF00FF00"));
            holder.tvSize.setTextColor(Color.parseColor("#FF00AA00"));
        }

        holder.itemContainer.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            listener.onFileClick(files.get(pos), pos);
        });

        holder.itemContainer.setOnLongClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return false;
            listener.onFileLongClick(files.get(pos), pos);
            return true;
        });
    }

    private String getDirectorySizeText(File dir) {
        int count = 0;
        File[] children = dir.listFiles();
        if (children != null) count = children.length;
        return count + " items";
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    @Override
    public int getItemCount() {
        return files.size();
    }

    static class FileViewHolder extends RecyclerView.ViewHolder {
        LinearLayout itemContainer;
        ImageView ivIcon;
        TextView tvName;
        TextView tvSize;

        FileViewHolder(@NonNull View itemView) {
            super(itemView);
            itemContainer = itemView.findViewById(R.id.item_container);
            ivIcon = itemView.findViewById(R.id.iv_icon);
            tvName = itemView.findViewById(R.id.tv_name);
            tvSize = itemView.findViewById(R.id.tv_size);
        }
    }
}   