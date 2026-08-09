package ohi.andre.consolelauncher;

import android.os.Build;
import android.os.Bundle;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import android.graphics.Color;

public class HistoryActivity extends AppCompatActivity {

    private TextView historyList;
    private ScrollView historyScroll;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }

        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        setContentView(R.layout.activity_history);

        // Get root view by ID - now it exists!
        View root = findViewById(R.id.history_root);
        if (root != null) {
            root.setBackgroundColor(Color.TRANSPARENT);
        }

        historyList = findViewById(R.id.history_list);
        historyScroll = findViewById(R.id.history_scroll);

        // Load history from CalculatorActivity
        String history = CalculatorActivity.getHistory();
        if (history != null && !history.isEmpty()) {
            historyList.setText(history);
        } else {
            historyList.setText("No history yet");
        }

        // Back button
        ImageButton btnBack = findViewById(R.id.btn_back_history);
        btnBack.setOnClickListener(v -> finish());

        // Clear history button
        Button btnClear = findViewById(R.id.btn_clear_history);
        btnClear.setOnClickListener(v -> {
            CalculatorActivity.clearHistory();
            historyList.setText("No history yet");
        });

        // Auto-scroll to bottom
        historyScroll.post(() -> {
            historyScroll.fullScroll(View.FOCUS_DOWN);
        });
    }

    @Override
    public void onBackPressed() {
        super.onBackPressed();
        finish();
    }
}