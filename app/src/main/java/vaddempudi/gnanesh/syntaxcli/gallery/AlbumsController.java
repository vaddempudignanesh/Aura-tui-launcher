
package vaddempudi.gnanesh.syntaxcli.gallery;

import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.ProgressBar;
import android.widget.TextView;

import androidx.recyclerview.widget.RecyclerView;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import vaddempudi.gnanesh.syntaxcli.R;

final class AlbumsController {

    interface Host {
        View findView(int id);
        void onAlbumOpened(AlbumRecord album);
        void onAlbumClosed();
    }

    // Generation counter — invalidates stale async results.
    private final AtomicLong generation = new AtomicLong(0);

    private final AlbumLoader loader;
    private final AlbumAdapter albumAdapter;
    private final GalleryAdapter mediaAdapter;
    private final ExecutorService io = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());

    private final Host host;

    // Views
    private final RecyclerView albumRecycler;
    private final RecyclerView mediaRecycler;
    private final ProgressBar spinner;
    private final TextView title;
    private final View empty;

    // State
    private boolean showingAlbums = false;
    private AlbumRecord openAlbum = null;

    AlbumsController(Host host, AlbumLoader loader,
                     AlbumAdapter albumAdapter, GalleryAdapter mediaAdapter) {
        this.host = host;
        this.loader = loader;
        this.albumAdapter = albumAdapter;
        this.mediaAdapter = mediaAdapter;

        this.albumRecycler = (RecyclerView) host.findView(R.id.albumRecycler);
        this.mediaRecycler = (RecyclerView) host.findView(R.id.galleryRecycler);
        this.spinner = (ProgressBar) host.findView(R.id.loadingSpinner);
        this.title = (TextView) host.findView(R.id.titleGallery);
        this.empty = host.findView(R.id.emptyStateContainer);

        if (albumRecycler != null) {
            albumRecycler.setLayoutManager(
                    new androidx.recyclerview.widget.GridLayoutManager(
                            albumRecycler.getContext(), 2));
            albumRecycler.setAdapter(albumAdapter);
            albumRecycler.setItemViewCacheSize(12);
        }
    }

    boolean isShowingAlbums() { return showingAlbums; }
    AlbumRecord getOpenAlbum() { return openAlbum; }
    boolean isAlbumOpen() { return openAlbum != null; }

    // ── Public entry points ────────────────────────────────────────

    void showAlbums() {
        if (showingAlbums && openAlbum == null) return;

        showingAlbums = true;
        openAlbum = null;

        if (title != null) title.setText("Albums");
        if (mediaRecycler != null) mediaRecycler.setVisibility(View.GONE);
        if (albumRecycler != null) albumRecycler.setVisibility(View.GONE);
        if (empty != null) empty.setVisibility(View.GONE);
        if (spinner != null) spinner.setVisibility(View.VISIBLE);

        final long gen = generation.incrementAndGet();
        io.execute(() -> {
            final List<AlbumRecord> albums = loader.loadAlbums();
            main.post(() -> {
                if (gen != generation.get()) return;
                if (!showingAlbums || openAlbum != null) return;

                albumAdapter.submit(albums);
                if (spinner != null) spinner.setVisibility(View.GONE);
                if (albumRecycler != null) {
                    albumRecycler.setVisibility(albums.isEmpty() ? View.GONE : View.VISIBLE);
                }
                if (empty != null) {
                    empty.setVisibility(albums.isEmpty() ? View.VISIBLE : View.GONE);
                    TextView emptyText = (TextView) host.findView(R.id.emptyStateText);
                    if (emptyText != null) emptyText.setText("No albums");
                }
            });
        });
    }

    void openAlbum(AlbumRecord album) {
        if (album == null) return;

        openAlbum = album;
        showingAlbums = false;

        if (title != null) title.setText(album.displayName);
        if (albumRecycler != null) albumRecycler.setVisibility(View.GONE);
        if (mediaRecycler != null) mediaRecycler.setVisibility(View.GONE);
        if (empty != null) empty.setVisibility(View.GONE);
        if (spinner != null) spinner.setVisibility(View.VISIBLE);

        // Blank the media list immediately so nothing stale can render.
        mediaAdapter.submitList(new ArrayList<>());

        final long gen = generation.incrementAndGet();
        io.execute(() -> {
            final List<GalleryMediaItem> items = loader.loadAlbumItems(album);
            main.post(() -> {
                if (gen != generation.get()) return;
                if (openAlbum == null || !openAlbum.path.equals(album.path)) return;

                mediaAdapter.submitList(items);
                if (spinner != null) spinner.setVisibility(View.GONE);

                boolean hasItems = items != null && !items.isEmpty();
                if (mediaRecycler != null) {
                    mediaRecycler.setVisibility(hasItems ? View.VISIBLE : View.GONE);
                }
                if (empty != null) {
                    empty.setVisibility(hasItems ? View.GONE : View.VISIBLE);
                    TextView emptyText = (TextView) host.findView(R.id.emptyStateText);
                    if (emptyText != null) emptyText.setText("No media found");
                }

                host.onAlbumOpened(album);
            });
        });
    }

    /** Returns true if we handled the back press. */
    boolean onBack() {
        if (openAlbum != null) {
            closeAlbum();
            return true;
        }
        if (showingAlbums) {
            closeAlbumList();
            return true;
        }
        return false;
    }

    private void closeAlbum() {
        openAlbum = null;
        generation.incrementAndGet();

        mediaAdapter.submitList(new ArrayList<>());
        host.onAlbumClosed();

        // Then immediately show the album list again.
        showAlbums();
    }

    private void closeAlbumList() {
        showingAlbums = false;
        generation.incrementAndGet();
        if (albumRecycler != null) albumRecycler.setVisibility(View.GONE);
    }
}