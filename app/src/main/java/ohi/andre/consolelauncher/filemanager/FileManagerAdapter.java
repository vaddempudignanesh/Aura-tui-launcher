package ohi.andre.consolelauncher.filemanager;

import android.content.Context;
import android.graphics.Color;
import android.os.Handler;
import android.os.Looper;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.AnimationUtils;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import ohi.andre.consolelauncher.R;

public class FileManagerAdapter
        extends RecyclerView.Adapter<FileManagerAdapter.FileViewHolder> {

    public interface OnFileClickListener {
        void onFileClick(File file, int position);
        void onFileLongClick(File file, int position);
        void onSelectionChanged(int count);
    }

    private static final int AUTO_SCROLL_INTERVAL_MS = 16;
    private static final int AUTO_SCROLL_STEP_PX = 12;
    private static final int EDGE_ZONE_DP = 72;
    private static final int TAP_SLOP_DP = 12;

    private final Context context;
    private final List<File> files = new ArrayList<>();
    private final Set<File> selectedFiles = new HashSet<>();
    private final OnFileClickListener listener;
    private boolean selectionMode = false;
    private java.util.List<FileManagerActivity.SearchResult> searchResults = null;

    private boolean swipeSelecting = false;
    private boolean swipeSelectAdds = true;
    private int swipeAnchor = -1;
    private int lastTouchedPosition = -1;

    private float downX, downY;
    private boolean dragStarted = false;
    private boolean longPressFired = false;

    private final RecyclerView recycler;
    private final Handler autoScrollHandler = new Handler(Looper.getMainLooper());
    private final int edgeZonePx;
    private final int tapSlopPx;
    private final int autoScrollStepPx;
    private int autoScrollDirection = 0;

    private final Runnable autoScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (autoScrollDirection == 0) return;
            int scrolled = recycler.canScrollVertically(autoScrollDirection)
                    ? autoScrollDirection * autoScrollStepPx : 0;
            if (scrolled != 0) {
                recycler.scrollBy(0, scrolled);
                extendSelectionAtEdge();
            }
            autoScrollHandler.postDelayed(this, AUTO_SCROLL_INTERVAL_MS);
        }
    };

    public FileManagerAdapter(Context context, RecyclerView recycler,
                              OnFileClickListener listener) {
        this.context = context;
        this.recycler = recycler;
        this.listener = listener;
        this.edgeZonePx = (int) (EDGE_ZONE_DP * context.getResources().getDisplayMetrics().density);
        this.tapSlopPx = (int) (TAP_SLOP_DP * context.getResources().getDisplayMetrics().density);
        this.autoScrollStepPx = (int) (AUTO_SCROLL_STEP_PX * context.getResources().getDisplayMetrics().density);

        final GestureDetector detector =
                new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public void onLongPress(MotionEvent e) {
                        longPressFired = true;
                        int pos = positionFromEvent(e);
                        if (pos == RecyclerView.NO_POSITION) return;

                        File f = files.get(pos);
                        if (!selectionMode) {
                            selectionMode = true;
                            selectedFiles.clear();
                            selectedFiles.add(f);
                            swipeAnchor = pos;
                            swipeSelectAdds = true;
                            swipeSelecting = true;
                            lastTouchedPosition = pos;
                            notifyItemChanged(pos);
                            listener.onSelectionChanged(selectedFiles.size());
                            haptic();
                        } else {
                            swipeAnchor = pos;
                            swipeSelectAdds = !selectedFiles.contains(f);
                            toggleSelectionQuiet(f);
                            notifyItemChanged(pos);
                            swipeSelecting = true;
                            lastTouchedPosition = pos;
                            haptic();
                        }
                    }
                });

        recycler.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv,
                                                 @NonNull MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN:
                        downX = e.getX();
                        downY = e.getY();
                        dragStarted = false;
                        longPressFired = false;
                        lastTouchedPosition = positionFromEvent(e);
                        detector.onTouchEvent(e);
                        return false;
                    case MotionEvent.ACTION_MOVE:
                        float dx = Math.abs(e.getX() - downX);
                        float dy = Math.abs(e.getY() - downY);

                        if (!dragStarted && (dx > tapSlopPx || dy > tapSlopPx)) {
                            dragStarted = true;
                            if (selectionMode && lastTouchedPosition != RecyclerView.NO_POSITION) {
                                File f = files.get(lastTouchedPosition);
                                swipeAnchor = lastTouchedPosition;
                                swipeSelectAdds = !selectedFiles.contains(f);
                                swipeSelecting = true;
                            }
                        }

                        detector.onTouchEvent(e);
                        if (swipeSelecting) {
                            handleSwipeMove(e);
                            updateAutoScroll(e);
                            return true;
                        }
                        return false;
                }
                return false;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                detector.onTouchEvent(e);

                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_MOVE:
                        if (swipeSelecting) {
                            handleSwipeMove(e);
                            updateAutoScroll(e);
                        }
                        break;
                    case MotionEvent.ACTION_UP:
                    case MotionEvent.ACTION_CANCEL:
                        stopAutoScroll();
                        if (swipeSelecting) {
                            swipeSelecting = false;
                            swipeAnchor = -1;
                            lastTouchedPosition = -1;
                        } else if (!longPressFired && !dragStarted
                                && lastTouchedPosition != RecyclerView.NO_POSITION
                                && lastTouchedPosition < files.size()) {
                            View child = rv.findChildViewUnder(e.getX(), e.getY());
                            if (child != null) {
                                int pos = rv.getChildAdapterPosition(child);
                                if (pos == lastTouchedPosition) {
                                    listener.onFileClick(files.get(pos), pos);
                                }
                            }
                        }
                        longPressFired = false;
                        dragStarted = false;
                        lastTouchedPosition = -1;
                        break;
                }
            }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallow) {
            }
        });
    }

    private void haptic() {
        try {
            recycler.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        } catch (Exception ignored) { }
    }

    private void selectionHaptic() {
        try {
            recycler.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
        } catch (Exception ignored) { }
    }

    private int positionFromEvent(MotionEvent e) {
        View child = recycler.findChildViewUnder(e.getX(), e.getY());
        if (child == null) return RecyclerView.NO_POSITION;
        return recycler.getChildAdapterPosition(child);
    }

    private void handleSwipeMove(MotionEvent e) {
        if (!swipeSelecting) return;
        if (e.getActionMasked() != MotionEvent.ACTION_MOVE) return;

        int pos = positionFromEvent(e);
        if (pos == RecyclerView.NO_POSITION) return;
        if (pos < 0 || pos >= files.size()) return;

        if (pos == lastTouchedPosition) return;

        int lo = Math.min(lastTouchedPosition, pos);
        int hi = Math.max(lastTouchedPosition, pos);

        boolean changed = false;
        if (swipeSelectAdds) {
            for (int i = lo; i <= hi; i++) {
                File f = files.get(i);
                if (!selectedFiles.contains(f)) {
                    selectedFiles.add(f);
                    notifyItemChanged(i);
                    changed = true;
                }
            }
        } else {
            for (int i = lo; i <= hi; i++) {
                File f = files.get(i);
                if (selectedFiles.contains(f)) {
                    selectedFiles.remove(f);
                    notifyItemChanged(i);
                    changed = true;
                }
            }
        }

        lastTouchedPosition = pos;

        if (changed) {
            selectionHaptic();
            listener.onSelectionChanged(selectedFiles.size());
        }
    }

    private void extendSelectionAtEdge() {
        int first = recycler.getChildCount() > 0
                ? recycler.getChildAdapterPosition(recycler.getChildAt(0))
                : RecyclerView.NO_POSITION;
        int last = recycler.getChildCount() > 0
                ? recycler.getChildAdapterPosition(recycler.getChildAt(recycler.getChildCount() - 1))
                : RecyclerView.NO_POSITION;

        if (first == RecyclerView.NO_POSITION || last == RecyclerView.NO_POSITION) return;

        int target = autoScrollDirection > 0 ? last : first;
        if (target < 0 || target >= files.size()) return;

        int lo = Math.min(swipeAnchor, target);
        int hi = Math.max(swipeAnchor, target);

        boolean changed = false;
        if (swipeSelectAdds) {
            for (int i = lo; i <= hi; i++) {
                File f = files.get(i);
                if (!selectedFiles.contains(f)) {
                    selectedFiles.add(f);
                    notifyItemChanged(i);
                    changed = true;
                }
            }
        } else {
            for (int i = lo; i <= hi; i++) {
                File f = files.get(i);
                if (selectedFiles.contains(f)) {
                    selectedFiles.remove(f);
                    notifyItemChanged(i);
                    changed = true;
                }
            }
        }

        lastTouchedPosition = target;
        if (changed) listener.onSelectionChanged(selectedFiles.size());
    }

    private void updateAutoScroll(MotionEvent e) {
        float y = e.getY();
        int h = recycler.getHeight();

        int newDir;
        if (y < edgeZonePx) {
            newDir = -1;
        } else if (y > h - edgeZonePx) {
            newDir = 1;
        } else {
            newDir = 0;
        }

        if (newDir != autoScrollDirection) {
            autoScrollDirection = newDir;
            autoScrollHandler.removeCallbacks(autoScrollRunnable);
            if (newDir != 0) {
                autoScrollHandler.post(autoScrollRunnable);
            }
        }
    }

    private void stopAutoScroll() {
        autoScrollDirection = 0;
        autoScrollHandler.removeCallbacks(autoScrollRunnable);
    }

    public void setSearchResults(java.util.List<FileManagerActivity.SearchResult> results) {
        this.searchResults = results;
        notifyDataSetChanged();
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
            swipeSelecting = false;
            swipeAnchor = -1;
            lastTouchedPosition = -1;
            stopAutoScroll();
        }
        notifyDataSetChanged();
        listener.onSelectionChanged(selectedFiles.size());
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
        listener.onSelectionChanged(selectedFiles.size());
    }

    private void toggleSelectionQuiet(File file) {
        if (selectedFiles.contains(file)) {
            selectedFiles.remove(file);
        } else {
            selectedFiles.add(file);
        }
        if (selectedFiles.isEmpty()) {
            selectionMode = false;
        }
    }

    public void selectAll() {
        selectedFiles.clear();
        selectedFiles.addAll(files);
        selectionMode = true;
        notifyDataSetChanged();
        listener.onSelectionChanged(selectedFiles.size());
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

        FileManagerActivity.SearchResult sr = null;
        if (searchResults != null && position < searchResults.size()) {
            sr = searchResults.get(position);
        }

        if (sr != null) {
            int slash = sr.relativePath.lastIndexOf('/');
            String parentRel = (slash >= 0) ? sr.relativePath.substring(0, slash) : "";
            holder.tvSize.setText(parentRel.isEmpty()
                    ? "•  current folder"
                    : "📁 " + parentRel);
        } else {
            holder.tvSize.setText(file.isDirectory()
                    ? getDirectorySizeText(file)
                    : formatSize(file.length()));
        }

        holder.ivIcon.setTag(R.id.iv_icon, null);
        holder.ivIcon.setImageDrawable(null);
        holder.ivIcon.setScaleType(ImageView.ScaleType.CENTER_INSIDE);
        int pad = (int) (10 * context.getResources().getDisplayMetrics().density);
        holder.ivIcon.setPadding(pad, pad, pad, pad);

        if (file.isDirectory()) {
            holder.ivIcon.setImageResource(R.drawable.ic_folder);
        } else if (isImageFile(file)
                && context instanceof FileManagerActivity
                && file.length() > 0
                && !FileManagerRootHelper.needsRoot(file.getAbsolutePath())) {

            android.graphics.drawable.Drawable fallback =
                    androidx.core.content.ContextCompat.getDrawable(
                            context, R.drawable.ic_file);
            holder.ivIcon.setImageDrawable(fallback);
            ((FileManagerActivity) context).loadThumbnail(
                    file, holder.ivIcon, fallback);
        } else {
            holder.ivIcon.setImageResource(R.drawable.ic_file);
        }

        if (isSelected) {
            holder.itemContainer.setBackgroundColor(Color.parseColor("#FF003300"));
            holder.tvName.setTextColor(Color.parseColor("#FF00FF00"));
            holder.tvSize.setTextColor(Color.parseColor("#FF00FF00"));
            holder.itemContainer.setScaleX(0.94f);
            holder.itemContainer.setScaleY(0.94f);
        } else {
            holder.itemContainer.setBackgroundColor(Color.parseColor("#FF000000"));
            holder.tvName.setTextColor(Color.parseColor("#FF00FF00"));
            holder.tvSize.setTextColor(Color.parseColor("#FF00AA00"));
            holder.itemContainer.setScaleX(1f);
            holder.itemContainer.setScaleY(1f);
        }

        holder.itemContainer.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            v.startAnimation(AnimationUtils.loadAnimation(
                    context, android.R.anim.fade_in));
            listener.onFileClick(files.get(pos), pos);
        });
    }

    private static boolean isImageFile(File f) {
        String n = f.getName().toLowerCase(Locale.US);
        return n.endsWith(".jpg") || n.endsWith(".jpeg") || n.endsWith(".png")
                || n.endsWith(".gif") || n.endsWith(".webp") || n.endsWith(".bmp")
                || n.endsWith(".heic") || n.endsWith(".heif");
    }

    private String getDirectorySizeText(File dir) {
        int count = 0;
        File[] children = dir.listFiles();
        if (children != null) count = children.length;
        return count + " items";
    }

    public static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.US, "%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024));
        return String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024));
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