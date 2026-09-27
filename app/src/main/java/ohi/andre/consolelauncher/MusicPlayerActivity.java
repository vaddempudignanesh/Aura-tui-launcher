package ohi.andre.consolelauncher;

import android.Manifest;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Typeface;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.MediaStore;
import android.provider.Settings;
import android.text.InputType;
import android.view.ContextThemeWrapper;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import java.io.File;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MusicPlayerActivity extends AppCompatActivity {

    // ---------- Colors ----------
    private static final int BG      = 0xFF000000;
    private static final int GREEN   = 0xFF00FF00;
    private static final int DIM     = 0xFF00AA00;
    private static final int ROW_SEL = 0xFF003300;
    private static final int NOW_PLAY = 0xFFFFFF00;

    // ---------- View states ----------
    private static final int VIEW_LIBRARY  = 0;
    private static final int VIEW_PLAYLIST = 1;
    private static final int VIEW_PICKER   = 2;
    private int viewState = VIEW_LIBRARY;

    // ---------- Sort modes ----------
    private static final int SORT_LATEST = 0;
    private static final int SORT_OLDEST = 1;
    private static final int SORT_AZ     = 2;
    private static final int SORT_ZA     = 3;
    private int sortMode = SORT_AZ;

    // ---------- Permission ----------
    private static final int REQ_AUDIO = 3001;

    // ---------- Data ----------
    public static class Track {
        long id;
        String title;
        String artist;
        String path;
        Uri contentUri;
        long durationMs;
        long dateAdded;      // seconds since epoch (MediaStore index time)
        long dateModified;   // seconds since epoch (file mtime)
        long size;
    }

    private final List<Track> allTracks     = new ArrayList<>();
    private final List<Track> visibleTracks = new ArrayList<>();

    private final List<Long> activePlaylistIds = new ArrayList<>();
    private boolean filterActive = false;
    private final Set<Long> pickBuffer = new HashSet<>();

    // ---------- Views ----------
    private RecyclerView recycler;
    private TrackAdapter adapter;
    private TextView     tvTitle;
    private TextView     emptyLabel;
    private ImageView    btnLeft;
    private ImageView    btnSort;
    private Button       btnAdd;

    // Bottom bar
    private LinearLayout bottomBar;
    private TextView     tvNowPlaying;
    private SeekBar      seekBar;

    // ---------- Player ----------
    private MediaPlayer player;
    private int playingIndex = -1;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    private final Runnable progressTick = new Runnable() {
        @Override public void run() {
            if (player != null) {
                try {
                    int pos = player.getCurrentPosition();
                    int dur = player.getDuration();
                    if (dur > 0) seekBar.setMax(dur);
                    seekBar.setProgress(pos);
                } catch (Exception ignored) { }
                ui.postDelayed(this, 500);
            }
        }
    };

    // ============================================================
    // Lifecycle
    // ============================================================

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        buildUi();

        if (hasAudioPermission()) {
            loadTracks();
        } else {
            requestAudioPermission();
        }
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ui.removeCallbacksAndMessages(null);
        io.shutdownNow();
        // Only release the player when the user actually leaves the task
        // (swipe from recents / back at library view). If the OS is just
        // reclaiming the activity while we are backgrounded, keep playing.
        if (isFinishing()) {
            releasePlayer();
        }
    }

    @Override
    protected void onStop() {
        super.onStop();
        // Do NOT pause the player — background playback is intentional.
    }

    @Override
    public void onBackPressed() {
        switch (viewState) {
            case VIEW_PICKER:
                pickBuffer.clear();
                viewState = filterActive ? VIEW_PLAYLIST : VIEW_LIBRARY;
                refreshList();
                updateToolbar();
                return;
            case VIEW_PLAYLIST:
                viewState = VIEW_LIBRARY;
                refreshList();
                updateToolbar();
                return;
            default:
                super.onBackPressed();
        }
    }

    // ============================================================
    // UI
    // ============================================================

    private void buildUi() {
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(BG);

        // ---------- Top bar ----------
        LinearLayout topBar = new LinearLayout(this);
        topBar.setOrientation(LinearLayout.HORIZONTAL);
        topBar.setBackgroundColor(BG);
        topBar.setGravity(Gravity.CENTER_VERTICAL);
        topBar.setPadding(dp(8), dp(8), dp(8), dp(8));

        btnLeft = new ImageView(this);
        btnLeft.setImageResource(R.drawable.ic_menu);
        btnLeft.setColorFilter(GREEN);
        btnLeft.setOnClickListener(v -> onLeftIconTapped());
        topBar.addView(btnLeft, new LinearLayout.LayoutParams(dp(40), dp(40)));

        tvTitle = new TextView(this);
        tvTitle.setText("MUSIC");
        tvTitle.setTextColor(GREEN);
        tvTitle.setTextSize(16);
        tvTitle.setTypeface(Typeface.MONOSPACE);
        tvTitle.setSingleLine(true);
        tvTitle.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
        LinearLayout.LayoutParams titleLp = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        titleLp.leftMargin = dp(12);
        topBar.addView(tvTitle, titleLp);

        btnSort = new ImageView(this);
        btnSort.setImageResource(R.drawable.ic_sort);
        btnSort.setColorFilter(GREEN);
        btnSort.setOnClickListener(this::showSortMenu);
        LinearLayout.LayoutParams sortLp =
                new LinearLayout.LayoutParams(dp(40), dp(40));
        sortLp.gravity = Gravity.END;
        topBar.addView(btnSort, sortLp);

        root.addView(topBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        // ---------- List ----------
        recycler = new RecyclerView(this);
        recycler.setBackgroundColor(BG);
        recycler.setLayoutManager(new LinearLayoutManager(this));
        recycler.setItemAnimator(null);
        adapter = new TrackAdapter();
        recycler.setAdapter(adapter);
        root.addView(recycler, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f));

        // ---------- Empty label ----------
        emptyLabel = new TextView(this);
        emptyLabel.setTextColor(DIM);
        emptyLabel.setTextSize(13);
        emptyLabel.setTypeface(Typeface.MONOSPACE);
        emptyLabel.setGravity(Gravity.CENTER);
        emptyLabel.setVisibility(View.GONE);
        LinearLayout.LayoutParams emptyLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        emptyLp.topMargin = dp(24);
        root.addView(emptyLabel, emptyLp);

        // ---------- ADD button ----------
        btnAdd = new Button(this);
        btnAdd.setText("ADD");
        btnAdd.setTextColor(GREEN);
        btnAdd.setBackgroundColor(BG);
        btnAdd.setTypeface(Typeface.MONOSPACE);
        btnAdd.setTextSize(14);
        btnAdd.setAllCaps(false);
        btnAdd.setVisibility(View.GONE);
        btnAdd.setOnClickListener(v -> enterPicker());
        LinearLayout.LayoutParams addLp = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        addLp.leftMargin = dp(12);
        addLp.rightMargin = dp(12);
        addLp.bottomMargin = dp(6);
        root.addView(btnAdd, addLp);

        // ---------- Bottom bar ----------
        bottomBar = new LinearLayout(this);
        bottomBar.setOrientation(LinearLayout.VERTICAL);
        bottomBar.setBackgroundColor(BG);
        bottomBar.setPadding(dp(12), dp(6), dp(12), dp(6));
        bottomBar.setVisibility(View.GONE);

        tvNowPlaying = new TextView(this);
        tvNowPlaying.setTextColor(GREEN);
        tvNowPlaying.setTextSize(12);
        tvNowPlaying.setTypeface(Typeface.MONOSPACE);
        tvNowPlaying.setSingleLine(true);
        tvNowPlaying.setEllipsize(android.text.TextUtils.TruncateAt.MARQUEE);
        tvNowPlaying.setMarqueeRepeatLimit(-1);
        tvNowPlaying.setSelected(true);
        bottomBar.addView(tvNowPlaying, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        seekBar = new SeekBar(this);
        seekBar.setProgressTintList(android.content.res.ColorStateList.valueOf(GREEN));
        seekBar.setThumbTintList(android.content.res.ColorStateList.valueOf(GREEN));
        seekBar.setProgressBackgroundTintList(
                android.content.res.ColorStateList.valueOf(0xFF003300));
        seekBar.setPadding(0, dp(6), 0, dp(6));
        seekBar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar sb, int p, boolean fromUser) {
                if (fromUser && player != null) {
                    try { player.seekTo(p); } catch (Exception ignored) { }
                }
            }
            @Override public void onStartTrackingTouch(SeekBar sb) { }
            @Override public void onStopTrackingTouch(SeekBar sb) { }
        });
        bottomBar.addView(seekBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        root.addView(bottomBar, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        setContentView(root);
    }

    private void updateToolbar() {
        switch (viewState) {
            case VIEW_PLAYLIST:
                btnLeft.setImageResource(R.drawable.ic_back);
                tvTitle.setText("PLAYLIST");
                break;
            case VIEW_PICKER:
                btnLeft.setImageResource(R.drawable.ic_check);
                tvTitle.setText("SELECT TRACKS");
                break;
            case VIEW_LIBRARY:
            default:
                btnLeft.setImageResource(R.drawable.ic_menu);
                tvTitle.setText("MUSIC");
                break;
        }
    }

    private void updateEmptyState() {
        if (emptyLabel == null || btnAdd == null) return;

        btnAdd.setVisibility(viewState == VIEW_PLAYLIST ? View.VISIBLE : View.GONE);

        if (visibleTracks.isEmpty()) {
            emptyLabel.setVisibility(View.VISIBLE);
            if (viewState == VIEW_PLAYLIST) {
                emptyLabel.setText("No playlist.\nTap ADD to choose tracks.");
            } else {
                emptyLabel.setText("No audio files on this device.");
            }
        } else {
            emptyLabel.setVisibility(View.GONE);
        }
    }

    private void onLeftIconTapped() {
        switch (viewState) {
            case VIEW_LIBRARY:
                viewState = VIEW_PLAYLIST;
                break;
            case VIEW_PLAYLIST:
                viewState = VIEW_LIBRARY;
                break;
            case VIEW_PICKER:
                activePlaylistIds.clear();
                activePlaylistIds.addAll(pickBuffer);
                pickBuffer.clear();
                filterActive = !activePlaylistIds.isEmpty();
                viewState = VIEW_PLAYLIST;
                break;
        }
        refreshList();
        updateToolbar();
    }

    private void enterPicker() {
        pickBuffer.clear();
        for (Long id : activePlaylistIds) pickBuffer.add(id);
        viewState = VIEW_PICKER;
        refreshList();
        updateToolbar();
    }

    // ============================================================
    // Permissions
    // ============================================================

    private boolean hasAudioPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            return ContextCompat.checkSelfPermission(this,
                    "android.permission.READ_MEDIA_AUDIO")
                    == PackageManager.PERMISSION_GRANTED;
        } else {
            return ContextCompat.checkSelfPermission(this,
                    Manifest.permission.READ_EXTERNAL_STORAGE)
                    == PackageManager.PERMISSION_GRANTED;
        }
    }

    private void requestAudioPermission() {
        if (Build.VERSION.SDK_INT >= 33) {
            ActivityCompat.requestPermissions(this,
                    new String[]{
                            "android.permission.READ_MEDIA_AUDIO",
                            "android.permission.POST_NOTIFICATIONS"
                    }, REQ_AUDIO);
        } else {
            ActivityCompat.requestPermissions(this,
                    new String[]{Manifest.permission.READ_EXTERNAL_STORAGE}, REQ_AUDIO);
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode,
                                           @NonNull String[] permissions,
                                           @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode != REQ_AUDIO) return;
        if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            loadTracks();
        } else {
            blackDialog()
                    .setTitle("Permission required")
                    .setMessage("Music Player needs access to audio files.\n\n" +
                            "Open settings?")
                    .setPositiveButton("Settings", (d, w) -> {
                        Intent i = new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                Uri.parse("package:" + getPackageName()));
                        startActivity(i);
                    })
                    .setNegativeButton("Close", (d, w) -> finish())
                    .show();
        }
    }

    // ============================================================
    // MediaStore query
    // ============================================================

    private void loadTracks() {
        io.execute(() -> {
            List<Track> found = new ArrayList<>();
            ContentResolver cr = getContentResolver();

            Uri collection = MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
            String[] projection = {
                    MediaStore.Audio.Media._ID,
                    MediaStore.Audio.Media.TITLE,
                    MediaStore.Audio.Media.ARTIST,
                    MediaStore.Audio.Media.DATA,
                    MediaStore.Audio.Media.DURATION,
                    MediaStore.Audio.Media.DATE_ADDED,
                    MediaStore.Audio.Media.DATE_MODIFIED,
                    MediaStore.Audio.Media.SIZE
            };
            String selection = MediaStore.Audio.Media.IS_MUSIC + "!=0"
                    + " OR " + MediaStore.Audio.Media.IS_PODCAST + "!=0";

            try (Cursor c = cr.query(collection, projection, selection,
                    null, null)) {
                if (c != null) {
                    int iId    = c.getColumnIndexOrThrow(MediaStore.Audio.Media._ID);
                    int iTitle = c.getColumnIndexOrThrow(MediaStore.Audio.Media.TITLE);
                    int iArt   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.ARTIST);
                    int iData  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATA);
                    int iDur   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DURATION);
                    int iAdd   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_ADDED);
                    int iMod   = c.getColumnIndexOrThrow(MediaStore.Audio.Media.DATE_MODIFIED);
                    int iSize  = c.getColumnIndexOrThrow(MediaStore.Audio.Media.SIZE);

                    while (c.moveToNext()) {
                        Track t = new Track();
                        t.id = c.getLong(iId);
                        t.title = c.getString(iTitle);
                        if (t.title == null || t.title.isEmpty()) t.title = "Unknown";
                        t.artist = c.getString(iArt);
                        if (t.artist == null) t.artist = "";
                        t.path = c.getString(iData);
                        t.durationMs = c.getLong(iDur);
                        t.dateAdded = c.getLong(iAdd);
                        t.dateModified = c.getLong(iMod);
                        t.size = c.getLong(iSize);
                        t.contentUri = ContentUris.withAppendedId(collection, t.id);
                        found.add(t);
                    }
                }
            } catch (Exception e) {
                FileLog.e("MusicPlayer: MediaStore query failed", e);
            }

            ui.post(() -> {
                allTracks.clear();
                allTracks.addAll(found);
                if (allTracks.isEmpty()) {
                    Toast.makeText(this, "No audio files found",
                            Toast.LENGTH_SHORT).show();
                }
                refreshList();
                updateToolbar();
            });
        });
    }

    // ============================================================
    // Refresh + sort
    // ============================================================

    private void refreshList() {
        List<Track> base;
        if (viewState == VIEW_PLAYLIST) {
            base = new ArrayList<>();
            if (filterActive && !activePlaylistIds.isEmpty()) {
                for (Long id : activePlaylistIds) {
                    for (Track t : allTracks) {
                        if (t.id == id) { base.add(t); break; }
                    }
                }
            }
        } else {
            base = new ArrayList<>(allTracks);
        }

        Comparator<Track> cmp;
        switch (sortMode) {
            case SORT_LATEST:
                // Prefer DATE_MODIFIED. If two files share the same mtime
                // (common when a batch was copied), fall back to file size
                // and then to title, so the order is at least deterministic.
                cmp = (a, b) -> {
                    int c1 = Long.compare(b.dateModified, a.dateModified);
                    if (c1 != 0) return c1;
                    int c2 = Long.compare(b.size, a.size);
                    if (c2 != 0) return c2;
                    return a.title.compareToIgnoreCase(b.title);
                };
                break;
            case SORT_OLDEST:
                cmp = (a, b) -> {
                    int c1 = Long.compare(a.dateModified, b.dateModified);
                    if (c1 != 0) return c1;
                    int c2 = Long.compare(a.size, b.size);
                    if (c2 != 0) return c2;
                    return a.title.compareToIgnoreCase(b.title);
                };
                break;
            case SORT_ZA:
                cmp = (a, b) -> b.title.compareToIgnoreCase(a.title);
                break;
            case SORT_AZ:
            default:
                cmp = (a, b) -> a.title.compareToIgnoreCase(b.title);
                break;
        }
        Collections.sort(base, cmp);

        visibleTracks.clear();
        visibleTracks.addAll(base);
        adapter.notifyDataSetChanged();
        updateEmptyState();
    }

    private void showSortMenu(View anchor) {
        ContextThemeWrapper w = new ContextThemeWrapper(this, R.style.PopupMenu_Black);
        PopupMenu popup = new PopupMenu(w, anchor);
        popup.getMenu().add(0, 1, 0, "Latest first");
        popup.getMenu().add(0, 2, 1, "Oldest first");
        popup.getMenu().add(0, 3, 2, "A \u2192 Z");
        popup.getMenu().add(0, 4, 3, "Z \u2192 A");
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: sortMode = SORT_LATEST; break;
                case 2: sortMode = SORT_OLDEST; break;
                case 3: sortMode = SORT_AZ;     break;
                case 4: sortMode = SORT_ZA;     break;
            }
            refreshList();
            return true;
        });
        forceBlackPopupBackground(popup);
        popup.show();
    }

    // ============================================================
    // Playback
    // ============================================================

    private void playTrack(int index) {
        if (index < 0 || index >= visibleTracks.size()) return;
        Track t = visibleTracks.get(index);
        playingIndex = index;

        releasePlayer();
        try {
            player = new MediaPlayer();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.setDataSource(this, t.contentUri);
            player.setOnPreparedListener(mp -> {
                mp.start();
                bottomBar.setVisibility(View.VISIBLE);
                tvNowPlaying.setText("\u25B6  " + t.title
                        + (t.artist.isEmpty() ? "" : "  \u2013 " + t.artist));
                seekBar.setMax(mp.getDuration());
                seekBar.setProgress(0);
                ui.removeCallbacks(progressTick);
                ui.post(progressTick);
                adapter.notifyDataSetChanged();
            });
            player.setOnCompletionListener(mp -> {
                int next = playingIndex + 1;
                if (next < visibleTracks.size()) playTrack(next);
                else {
                    tvNowPlaying.setText("Stopped");
                    seekBar.setProgress(0);
                }
            });
            player.setOnErrorListener((mp, what, extra) -> {
                Toast.makeText(this, "Playback error", Toast.LENGTH_SHORT).show();
                return true;
            });
            player.prepareAsync();
            try {
                Intent svc = new Intent(this, MusicPlaybackService.class);
                if (Build.VERSION.SDK_INT >= 26) {
                    startForegroundService(svc);
                } else {
                    startService(svc);
                }
            } catch (Exception ignored) { }
        } catch (Exception e) {
            FileLog.e("MusicPlayer: play failed", e);
            Toast.makeText(this, "Cannot play: " + e.getMessage(),
                    Toast.LENGTH_SHORT).show();
        }
    }

    private void releasePlayer() {
        ui.removeCallbacks(progressTick);
        if (player != null) {
            try { player.stop(); } catch (Exception ignored) { }
            try { player.release(); } catch (Exception ignored) { }
            player = null;
        }
        try {
            stopService(new Intent(this, MusicPlaybackService.class));
        } catch (Exception ignored) { }
    }

    // ============================================================
    // Row actions
    // ============================================================

    private void showTrackActions(View anchor, Track track) {
        ContextThemeWrapper w = new ContextThemeWrapper(this, R.style.PopupMenu_Black);
        PopupMenu popup = new PopupMenu(w, anchor);
        popup.getMenu().add(0, 1, 0, "Delete");
        popup.getMenu().add(0, 2, 1, "Add to playlist");
        popup.getMenu().add(0, 3, 2, "Rename");

        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()) {
                case 1: confirmDelete(track); return true;
                case 2: addToPlaylist(track); return true;
                case 3: showRenameDialog(track); return true;
            }
            return false;
        });
        forceBlackPopupBackground(popup);
        popup.show();
    }

    private void confirmDelete(Track track) {
        blackDialog()
                .setTitle("Delete")
                .setMessage("Delete \"" + track.title + "\" from device?")
                .setPositiveButton("Delete", (d, w) -> {
                    try {
                        int rows = getContentResolver().delete(
                                track.contentUri, null, null);
                        if (rows > 0) {
                            Toast.makeText(this, "Deleted",
                                    Toast.LENGTH_SHORT).show();
                            allTracks.remove(track);
                            visibleTracks.remove(track);
                            activePlaylistIds.remove((Long) track.id);
                            pickBuffer.remove((Long) track.id);
                            releasePlayer();
                            playingIndex = -1;
                            bottomBar.setVisibility(View.GONE);
                            refreshList();
                        } else {
                            Toast.makeText(this, "Delete failed",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        FileLog.e("MusicPlayer: delete failed", e);
                        Toast.makeText(this, "Delete failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void addToPlaylist(Track track) {
        if (!activePlaylistIds.contains(track.id)) {
            activePlaylistIds.add(track.id);
        }
        filterActive = true;
        Toast.makeText(this, "Added to playlist (" + activePlaylistIds.size() + ")",
                Toast.LENGTH_SHORT).show();
    }

    private void showRenameDialog(Track track) {
        EditText input = new EditText(this);
        input.setText(track.title);
        input.setInputType(InputType.TYPE_CLASS_TEXT);
        input.setTextColor(GREEN);
        input.setHintTextColor(DIM);
        input.setBackgroundColor(BG);

        blackDialog()
                .setTitle("Rename")
                .setView(input)
                .setPositiveButton("Rename", (d, w) -> {
                    String nn = input.getText().toString().trim();
                    if (nn.isEmpty()) return;
                    try {
                        ContentValues cv = new ContentValues();
                        cv.put(MediaStore.Audio.Media.TITLE, nn);
                        int rows = getContentResolver().update(
                                track.contentUri, cv, null, null);
                        if (rows > 0) {
                            track.title = nn;
                            try {
                                if (track.path != null) {
                                    File src = new File(track.path);
                                    if (src.exists() && src.canWrite()) {
                                        String ext = "";
                                        int dot = src.getName().lastIndexOf('.');
                                        if (dot > 0)
                                            ext = src.getName().substring(dot);
                                        File dst = new File(
                                                src.getParentFile(), nn + ext);
                                        src.renameTo(dst);
                                    }
                                }
                            } catch (Exception ignored) { }
                            refreshList();
                        } else {
                            Toast.makeText(this, "Rename failed",
                                    Toast.LENGTH_SHORT).show();
                        }
                    } catch (Exception e) {
                        FileLog.e("MusicPlayer: rename failed", e);
                        Toast.makeText(this, "Rename failed: " + e.getMessage(),
                                Toast.LENGTH_LONG).show();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ============================================================
    // Adapter
    // ============================================================

    private class TrackAdapter extends RecyclerView.Adapter<TrackAdapter.VH> {

        @NonNull @Override
        public VH onCreateViewHolder(@NonNull ViewGroup parent, int viewType) {
            LinearLayout row = new LinearLayout(MusicPlayerActivity.this);
            row.setOrientation(LinearLayout.HORIZONTAL);
            row.setGravity(Gravity.CENTER_VERTICAL);
            row.setBackgroundColor(BG);
            row.setPadding(dp(12), dp(10), dp(8), dp(10));

            ImageView check = new ImageView(MusicPlayerActivity.this);
            check.setColorFilter(GREEN);
            check.setVisibility(View.GONE);
            row.addView(check, new LinearLayout.LayoutParams(dp(28), dp(28)));

            LinearLayout textCol = new LinearLayout(MusicPlayerActivity.this);
            textCol.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams textLp = new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
            textLp.leftMargin = dp(10);
            row.addView(textCol, textLp);

            TextView title = new TextView(MusicPlayerActivity.this);
            title.setTextColor(GREEN);
            title.setTextSize(14);
            title.setTypeface(Typeface.MONOSPACE);
            title.setSingleLine(true);
            title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textCol.addView(title);

            TextView sub = new TextView(MusicPlayerActivity.this);
            sub.setTextColor(DIM);
            sub.setTextSize(11);
            sub.setTypeface(Typeface.MONOSPACE);
            sub.setSingleLine(true);
            sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
            textCol.addView(sub);

            ImageView dots = new ImageView(MusicPlayerActivity.this);
            dots.setImageResource(R.drawable.ic_more_vert);
            dots.setColorFilter(GREEN);
            dots.setPadding(dp(6), dp(6), dp(6), dp(6));
            // Force right-alignment independent of text length.
            LinearLayout.LayoutParams dotsLp =
                    new LinearLayout.LayoutParams(dp(36), dp(36));
            dotsLp.gravity = Gravity.END | Gravity.CENTER_VERTICAL;
            // Push it hard to the right with a small right margin.
            dotsLp.rightMargin = dp(4);
            row.addView(dots, dotsLp);

            return new VH(row, check, title, sub, dots);
        }

        @Override
        public void onBindViewHolder(@NonNull VH h, int position) {
            Track t = visibleTracks.get(position);
            boolean pickerMode = (viewState == VIEW_PICKER);

            h.title.setText(t.title);
            StringBuilder sb = new StringBuilder();
            if (!t.artist.isEmpty()) sb.append(t.artist).append("  \u2022  ");
            sb.append(fmt(t.durationMs));
            h.sub.setText(sb.toString());

            if (pickerMode) {
                h.check.setVisibility(View.VISIBLE);
                h.check.setImageResource(pickBuffer.contains(t.id)
                        ? R.drawable.ic_checkbox_checked
                        : R.drawable.ic_checkbox_empty);
            } else {
                h.check.setVisibility(View.GONE);
            }

            boolean isPlaying = playingIndex >= 0
                    && playingIndex < visibleTracks.size()
                    && visibleTracks.get(playingIndex) == t;

            h.itemView.setBackgroundColor(isPlaying ? ROW_SEL : BG);
            h.title.setTextColor(isPlaying ? NOW_PLAY : GREEN);
            h.sub.setTextColor(isPlaying ? 0xFFAA8800 : DIM);
            h.dots.setVisibility(pickerMode ? View.GONE : View.VISIBLE);

            final int pos = h.getAdapterPosition();

            h.itemView.setOnClickListener(v -> {
                if (viewState == VIEW_PICKER) {
                    if (pickBuffer.contains(t.id)) pickBuffer.remove(t.id);
                    else pickBuffer.add(t.id);
                    notifyItemChanged(pos);
                } else {
                    playTrack(pos);
                }
            });

            h.dots.setOnClickListener(v -> {
                if (viewState != VIEW_PICKER) showTrackActions(v, t);
            });
        }

        @Override
        public int getItemCount() { return visibleTracks.size(); }

        class VH extends RecyclerView.ViewHolder {
            final ImageView check, dots;
            final TextView title, sub;
            VH(@NonNull View v, ImageView c, TextView t, TextView s, ImageView d) {
                super(v);
                check = c; title = t; sub = s; dots = d;
            }
        }
    }

    // ============================================================
    // Helpers
    // ============================================================

    private AlertDialog.Builder blackDialog() {
        return new AlertDialog.Builder(new ContextThemeWrapper(this, R.style.BlackDialog));
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }

    private static String fmt(long ms) {
        if (ms <= 0) return "--:--";
        long s = ms / 1000;
        long m = s / 60;
        long r = s % 60;
        return String.format(Locale.US, "%d:%02d", m, r);
    }

    private void forceBlackPopupBackground(PopupMenu popup) {
        try {
            java.lang.reflect.Field[] fields = popup.getClass().getDeclaredFields();
            for (java.lang.reflect.Field f : fields) {
                if (!"mPopup".equals(f.getName())) continue;
                f.setAccessible(true);
                Object helper = f.get(popup);
                try {
                    java.lang.reflect.Method m = helper.getClass()
                            .getMethod("setForceShowIcon", boolean.class);
                    m.invoke(helper, true);
                } catch (Exception ignored) { }
                try {
                    java.lang.reflect.Field pf = helper.getClass()
                            .getDeclaredField("mPopup");
                    pf.setAccessible(true);
                    Object lp = pf.get(helper);
                    java.lang.reflect.Method bg = lp.getClass()
                            .getMethod("setBackgroundDrawable",
                                    android.graphics.drawable.Drawable.class);
                    bg.invoke(lp, ContextCompat.getDrawable(this, R.drawable.popup_bg));
                } catch (Exception ignored) { }
                break;
            }
        } catch (Exception ignored) { }
    }
}