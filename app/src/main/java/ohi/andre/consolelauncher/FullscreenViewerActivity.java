package ohi.andre.consolelauncher;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.ImageButton;
import androidx.appcompat.app.AppCompatActivity;
import androidx.viewpager2.widget.ViewPager2;
import android.graphics.Color;
import java.util.ArrayList;
import java.util.List;

public class FullscreenViewerActivity extends AppCompatActivity {

    private ViewPager2 viewPager;
    private FullscreenAdapter adapter;
    private List<String> mediaPaths = new ArrayList<>();
    private int currentPosition = 0;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_fullscreen_viewer);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN);
            getWindow().clearFlags(WindowManager.LayoutParams.FLAG_FORCE_NOT_FULLSCREEN);
        }
        getWindow().setBackgroundDrawableResource(android.R.color.transparent);

        viewPager = findViewById(R.id.fullscreenViewPager);
        ImageButton btnClose = findViewById(R.id.btnCloseFullscreen);

        // Get data from intent
        mediaPaths = getIntent().getStringArrayListExtra("media_paths");
        currentPosition = getIntent().getIntExtra("current_position", 0);

        if (mediaPaths == null || mediaPaths.isEmpty()) {
            finish();
            return;
        }

        adapter = new FullscreenAdapter(mediaPaths);
        viewPager.setAdapter(adapter);
        viewPager.setCurrentItem(currentPosition, false);

        btnClose.setOnClickListener(v -> finish());

        viewPager.registerOnPageChangeCallback(new ViewPager2.OnPageChangeCallback() {
            @Override
            public void onPageSelected(int position) {
                super.onPageSelected(position);
                currentPosition = position;
            }
        });
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        finish();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
    }
}