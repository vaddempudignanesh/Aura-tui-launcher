package vaddempudi.gnanesh.syntaxcli.filemanager;

import android.content.Context;
import android.graphics.Color;
import android.view.GestureDetector;
import android.view.LayoutInflater;
import android.view.MotionEvent;
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
import java.util.Locale;
import java.util.Set;

import vaddempudi.gnanesh.syntaxcli.R;

public class FileManagerAdapter
        extends RecyclerView.Adapter<FileManagerAdapter.FileViewHolder> {

    public interface OnFileClickListener {
        void onFileClick(File file, int position);
        void onFileLongClick(File file, int position);
        /** Called whenever the selection set changes so the host can update its toolbar. */
        void onSelectionChanged(int count);
    }

    private final Context context;
    private final List<File> files = new ArrayList<>();
    private final Set<File> selectedFiles = new HashSet<>();
    private final OnFileClickListener listener;
    private boolean selectionMode = false;
    private java.util.List<FileManagerActivity.SearchResult> searchResults = null;

    // ── Swipe-select state ──
    // While a long-press is active AND the finger keeps moving vertically,
    // every row we pass through is added to (or removed from) the selection.
    private boolean swipeSelecting = false;
    /** true = add on enter, false = remove on enter. Decided at the start of the swipe. */
    private boolean swipeSelectAdds = true;
    /** The row index where the swipe started. The initial toggled row. */
    private int swipeAnchor = -1;
    /** Selection state that existed immediately after the swipe was started. */
    private final Set<File> swipeBaseSelection = new HashSet<>();
    /** Current range endpoint during this continuous drag. */
    private int swipeCurrentPosition = RecyclerView.NO_POSITION;
    private int autoScrollDirection = 0; // -1 = up, +1 = down

    private final RecyclerView recycler;

    // ── Bottom-edge auto-scroll selection ───────────────────────────────
    // When the user long-presses a file and drags to the bottom edge,
    // RecyclerView keeps scrolling while every newly reached row is selected.
    private final android.os.Handler autoScrollHandler =
            new android.os.Handler(android.os.Looper.getMainLooper());
    private boolean autoScrollRunning = false;
    private int lastAutoSelectedPosition = RecyclerView.NO_POSITION;
    private int lastAutoSelectedRow = RecyclerView.NO_POSITION;
    private static final int AUTO_SCROLL_EDGE_DP = 72;
    private static final int AUTO_SCROLL_STEP_DP = 18;
    private static final long AUTO_SCROLL_INTERVAL_MS = 32L;

    public FileManagerAdapter(Context context, RecyclerView recycler,
                              OnFileClickListener listener) {
        this.context = context;
        this.recycler = recycler;
        this.listener = listener;

        // Central gesture handling — no per-item OnLongClickListener.
        // This is the piece that makes long-press feel instant.
        final GestureDetector detector =
                new GestureDetector(context, new GestureDetector.SimpleOnGestureListener() {
                    @Override
                    public void onLongPress(MotionEvent e) {
                        int pos = positionFromEvent(e);
                        if (pos == RecyclerView.NO_POSITION) return;

                        File f = files.get(pos);
                        if (!selectionMode) {
                            selectionMode = true;
                            selectedFiles.clear();
                            selectedFiles.add(f);
                            swipeAnchor = pos;
                            // Decide add-vs-remove based on whether we just
                            // toggled the item off (impossible on first long
                            // press) or on (always true here).
                            swipeSelectAdds = true;
                            swipeSelecting = true;
                            swipeBaseSelection.clear();
                            swipeBaseSelection.addAll(selectedFiles);
                            swipeCurrentPosition = pos;
                            notifyDataSetChanged();
                            listener.onSelectionChanged(selectedFiles.size());
                            haptic();
                        } else {
                            // Already in selection mode — long-press starts
                            // a swipe-select from this row.
                            swipeAnchor = pos;
                            swipeSelectAdds = !selectedFiles.contains(f);
                            toggleSelection(f);
                            if (!selectionMode) return;
                            swipeSelecting = true;
                            swipeBaseSelection.clear();
                            swipeBaseSelection.addAll(selectedFiles);
                            swipeCurrentPosition = pos;
                            haptic();
                        }
                    }
                });

        recycler.addOnItemTouchListener(new RecyclerView.SimpleOnItemTouchListener() {
            @Override
            public boolean onInterceptTouchEvent(@NonNull RecyclerView rv,
                                                 @NonNull MotionEvent e) {
                detector.onTouchEvent(e);
                // Keep MOVE events under our control after long-press selection
                // starts. Bottom-edge auto-scroll below moves RecyclerView itself.
                return swipeSelecting && e.getActionMasked() == MotionEvent.ACTION_MOVE;
            }

            @Override
            public void onTouchEvent(@NonNull RecyclerView rv, @NonNull MotionEvent e) {
                handleSwipeMove(e);
                detector.onTouchEvent(e);

                if (e.getActionMasked() == MotionEvent.ACTION_UP
                        || e.getActionMasked() == MotionEvent.ACTION_CANCEL) {
                    stopBottomAutoScroll();
                    swipeSelecting = false;
                    swipeAnchor = -1;
                    swipeCurrentPosition = RecyclerView.NO_POSITION;
                    swipeBaseSelection.clear();
                    lastAutoSelectedPosition = RecyclerView.NO_POSITION;
                    lastAutoSelectedRow = RecyclerView.NO_POSITION;
                    autoScrollDirection = 0;
                }
            }

            @Override
            public void onRequestDisallowInterceptTouchEvent(boolean disallow) {
            }
        });
    }

    private void haptic() {
        try {
            recycler.performHapticFeedback(
                    android.view.HapticFeedbackConstants.LONG_PRESS);
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

        final float y = e.getY();
        final int height = recycler.getHeight();
        final int edgePx = dp(AUTO_SCROLL_EDGE_DP);

        // Bottom edge = scroll downward. Top edge = scroll upward.
        if (height > 0 && y >= height - edgePx) {
            startEdgeAutoScroll(1);
            return;
        }

        if (height > 0 && y <= edgePx) {
            startEdgeAutoScroll(-1);
            return;
        }

        stopBottomAutoScroll();

        int pos = positionFromEvent(e);
        if (pos != RecyclerView.NO_POSITION) {
            applySwipeEndpoint(pos);
        }
    }

    /**
     * Rebuild the selection for the current drag endpoint from the state that
     * existed when the long-press swipe began. This makes reverse dragging
     * genuinely undo the range instead of only adding more files.
     */
    private void applySwipeEndpoint(int targetPosition) {
        if (!swipeSelecting || targetPosition < 0 || targetPosition >= files.size()) return;
        if (swipeAnchor < 0 || swipeAnchor >= files.size()) return;

        swipeCurrentPosition = targetPosition;

        int first = Math.min(swipeAnchor, targetPosition);
        int last = Math.max(swipeAnchor, targetPosition);

        Set<File> newSelection = new HashSet<>(swipeBaseSelection);
        for (int position = first; position <= last; position++) {
            File file = files.get(position);
            if (swipeSelectAdds) {
                newSelection.add(file);
            } else {
                newSelection.remove(file);
            }
        }

        boolean changed = !selectedFiles.equals(newSelection);
        if (!changed) return;

        selectedFiles.clear();
        selectedFiles.addAll(newSelection);

        if (selectedFiles.isEmpty()) {
            selectionMode = false;
            stopBottomAutoScroll();
            swipeSelecting = false;
            notifyDataSetChanged();
            listener.onSelectionChanged(0);
            return;
        }

        notifyDataSetChanged();
        listener.onSelectionChanged(selectedFiles.size());
    }

    private void selectPositionForSwipe(int pos) {
        applySwipeEndpoint(pos);
    }

    private void startEdgeAutoScroll(int direction) {
        if (!swipeSelecting || files.isEmpty()) return;

        autoScrollDirection = direction;

        int endpoint = direction > 0
                ? getLastVisibleAdapterPosition()
                : getFirstVisibleAdapterPosition();

        if (endpoint == RecyclerView.NO_POSITION) return;

        applySwipeEndpoint(endpoint);

        // Already at the corresponding end of the adapter: there is nothing
        // further to scroll, but the endpoint itself remains selected.
        if ((direction > 0 && endpoint >= files.size() - 1)
                || (direction < 0 && endpoint <= 0)) {
            stopBottomAutoScroll();
            return;
        }

        if (autoScrollRunning) return;

        autoScrollRunning = true;
        autoScrollHandler.removeCallbacks(autoScrollRunnable);
        autoScrollHandler.post(autoScrollRunnable);
    }

    private final Runnable autoScrollRunnable = new Runnable() {
        @Override
        public void run() {
            if (!swipeSelecting || !autoScrollRunning || files.isEmpty()) return;

            int direction = autoScrollDirection;
            if (direction == 0) {
                stopBottomAutoScroll();
                return;
            }

            int endpointBefore = direction > 0
                    ? getLastVisibleAdapterPosition()
                    : getFirstVisibleAdapterPosition();

            if (endpointBefore == RecyclerView.NO_POSITION) {
                stopBottomAutoScroll();
                return;
            }

            applySwipeEndpoint(endpointBefore);
            if (!swipeSelecting) return;

            if ((direction > 0 && endpointBefore >= files.size() - 1)
                    || (direction < 0 && endpointBefore <= 0)) {
                stopBottomAutoScroll();
                return;
            }

            int distance = Math.max(1, dp(AUTO_SCROLL_STEP_DP));
            recycler.scrollBy(0, direction * distance);

            int endpointAfter = direction > 0
                    ? getLastVisibleAdapterPosition()
                    : getFirstVisibleAdapterPosition();

            if (endpointAfter != RecyclerView.NO_POSITION) {
                applySwipeEndpoint(endpointAfter);
                if (!swipeSelecting) return;
            }

            if ((direction > 0 && endpointAfter >= files.size() - 1)
                    || (direction < 0 && endpointAfter <= 0)) {
                stopBottomAutoScroll();
                return;
            }

            autoScrollHandler.postDelayed(this, AUTO_SCROLL_INTERVAL_MS);
        }
    };

    private void stopBottomAutoScroll() {
        autoScrollRunning = false;
        autoScrollDirection = 0;
        autoScrollHandler.removeCallbacks(autoScrollRunnable);
    }

    private int getLastVisibleAdapterPosition() {
        RecyclerView.LayoutManager lm = recycler.getLayoutManager();
        if (lm instanceof androidx.recyclerview.widget.LinearLayoutManager) {
            return ((androidx.recyclerview.widget.LinearLayoutManager) lm)
                    .findLastVisibleItemPosition();
        }
        return RecyclerView.NO_POSITION;
    }

    private int getFirstVisibleAdapterPosition() {
        RecyclerView.LayoutManager lm = recycler.getLayoutManager();
        if (lm instanceof androidx.recyclerview.widget.LinearLayoutManager) {
            return ((androidx.recyclerview.widget.LinearLayoutManager) lm)
                    .findFirstVisibleItemPosition();
        }
        return RecyclerView.NO_POSITION;
    }

    private int dp(int value) {
        return Math.round(value * context.getResources().getDisplayMetrics().density);
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
            stopBottomAutoScroll();
            swipeSelecting = false;
            swipeAnchor = -1;
            swipeCurrentPosition = RecyclerView.NO_POSITION;
            swipeBaseSelection.clear();
            lastAutoSelectedPosition = RecyclerView.NO_POSITION;
            lastAutoSelectedRow = RecyclerView.NO_POSITION;
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
            stopBottomAutoScroll();
            swipeSelecting = false;
            swipeAnchor = -1;
            swipeCurrentPosition = RecyclerView.NO_POSITION;
            swipeBaseSelection.clear();
        }
        notifyDataSetChanged();
        listener.onSelectionChanged(selectedFiles.size());
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

        // ── Icon / thumbnail ─────────────────────────────────
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

        // ── Colours ─────────────────────────────────────────────
        if (isSelected) {
            holder.itemContainer.setBackgroundColor(Color.parseColor("#FF003300"));
            holder.tvName.setTextColor(Color.parseColor("#FF00FF00"));
            holder.tvSize.setTextColor(Color.parseColor("#FF00FF00"));
        } else {
            holder.itemContainer.setBackgroundColor(Color.parseColor("#FF000000"));
            holder.tvName.setTextColor(Color.parseColor("#FF00FF00"));
            holder.tvSize.setTextColor(Color.parseColor("#FF00AA00"));
        }

        // ── Clicks ──────────────────────────────────────────────
        holder.itemContainer.setOnClickListener(v -> {
            int pos = holder.getAdapterPosition();
            if (pos == RecyclerView.NO_POSITION) return;
            listener.onFileClick(files.get(pos), pos);
        });

        // No setOnLongClickListener — long-press is handled globally by the
        // OnItemTouchListener above, which fires instantly.
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
    public void onDetachedFromRecyclerView(@NonNull RecyclerView recyclerView) {
        stopBottomAutoScroll();
        lastAutoSelectedPosition = RecyclerView.NO_POSITION;
        lastAutoSelectedRow = RecyclerView.NO_POSITION;
        super.onDetachedFromRecyclerView(recyclerView);
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