package ohi.andre.consolelauncher;

import android.os.Build;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewTreeObserver;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import android.content.Intent;
import android.graphics.Color;
import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

public class CalculatorActivity extends AppCompatActivity {

    private TextView display;
    private View blurTarget;
    private String currentInput = "";
    private String operator = "";
    private double firstNumber = 0;
    private boolean isNewInput = true;
    private boolean isOperatorPressed = false;
    private DecimalFormat decimalFormat = new DecimalFormat("#.##########");

    // Static history shared between activities
    private static List<String> historyList = new ArrayList<>();

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            getWindow().addFlags(WindowManager.LayoutParams.FLAG_DRAWS_SYSTEM_BAR_BACKGROUNDS);
            getWindow().setStatusBarColor(Color.TRANSPARENT);
            getWindow().setNavigationBarColor(Color.TRANSPARENT);
        }

        getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        setContentView(R.layout.activity_calculator);

        View root = findViewById(R.id.calc_root);
        root.setBackgroundColor(Color.TRANSPARENT);

        // The view whose rendered content gets frosted-glass blurred.
        // It sits directly behind the glass_panel, so it shows/blurs
        // whatever is drawn beneath it (launcher UI / wallpaper).
        blurTarget = findViewById(R.id.blur_target);
        applyFrostedGlassBlur();

        display = findViewById(R.id.calc_display);
        display.setSelected(true);

        setupButtons();
        updateDisplay();
    }

    /**
     * Applies the true real-time blur (RenderEffect, API 31+).
     * minSdk is 32 in this project, so this always runs on real devices,
     * but we still keep the version guard for safety/lint.
     */
    private void applyFrostedGlassBlur() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && blurTarget != null) {
            blurTarget.getViewTreeObserver().addOnGlobalLayoutListener(
                    new ViewTreeObserver.OnGlobalLayoutListener() {
                        @Override
                        public void onGlobalLayout() {
                            GlassBlurHelper.applyBlur(blurTarget);
                            blurTarget.getViewTreeObserver().removeOnGlobalLayoutListener(this);
                        }
                    }
            );
        }
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Some launcher window configs drop the RenderEffect on redraw;
        // reapplying on focus regain keeps the frost consistent.
        if (hasFocus && blurTarget != null) {
            GlassBlurHelper.applyBlur(blurTarget);
        }
    }

    // Static method to get history
    public static String getHistory() {
        if (historyList.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder();
        for (String entry : historyList) {
            sb.append(entry).append("\n");
        }
        return sb.toString();
    }

    // Static method to clear history
    public static void clearHistory() {
        historyList.clear();
    }

    private void setupButtons() {
        // Numbers 0-9
        int[] numberIds = {
                R.id.btn_0, R.id.btn_1, R.id.btn_2, R.id.btn_3, R.id.btn_4,
                R.id.btn_5, R.id.btn_6, R.id.btn_7, R.id.btn_8, R.id.btn_9
        };

        for (int id : numberIds) {
            Button btn = findViewById(id);
            btn.setOnClickListener(v -> {
                appendNumber(((Button) v).getText().toString());
            });
            setupButtonAnimation(btn);
        }

        // Operator buttons
        Button btnPlus = findViewById(R.id.btn_plus);
        btnPlus.setOnClickListener(v -> setOperator("+"));
        setupButtonAnimation(btnPlus);

        Button btnMinus = findViewById(R.id.btn_minus);
        btnMinus.setOnClickListener(v -> setOperator("-"));
        setupButtonAnimation(btnMinus);

        Button btnMultiply = findViewById(R.id.btn_multiply);
        btnMultiply.setOnClickListener(v -> setOperator("×"));
        setupButtonAnimation(btnMultiply);

        Button btnDivide = findViewById(R.id.btn_divide);
        btnDivide.setOnClickListener(v -> setOperator("÷"));
        setupButtonAnimation(btnDivide);

        Button btnEquals = findViewById(R.id.btn_equals);
        btnEquals.setOnClickListener(v -> calculate());
        setupButtonAnimation(btnEquals);

        Button btnClear = findViewById(R.id.btn_clear);
        btnClear.setOnClickListener(v -> clear());
        setupButtonAnimation(btnClear);

        Button btnDecimal = findViewById(R.id.btn_decimal);
        btnDecimal.setOnClickListener(v -> appendDecimal());
        setupButtonAnimation(btnDecimal);

        Button btnBackspace = findViewById(R.id.btn_backspace);
        btnBackspace.setOnClickListener(v -> backspace());
        setupButtonAnimation(btnBackspace);

        // History button - opens HistoryActivity
        Button btnHistory = findViewById(R.id.btn_history);
        btnHistory.setOnClickListener(v -> {
            Intent intent = new Intent(CalculatorActivity.this, HistoryActivity.class);
            startActivity(intent);
        });
        setupButtonAnimation(btnHistory);

        // Back button (ImageButton)
        ImageButton btnBack = findViewById(R.id.btn_back);
        btnBack.setOnClickListener(v -> finish());
        setupImageButtonAnimation(btnBack);
    }

    private void appendNumber(String num) {
        if (isOperatorPressed) {
            currentInput = num;
            isOperatorPressed = false;
            isNewInput = false;
        } else if (isNewInput) {
            currentInput = num;
            isNewInput = false;
        } else {
            if (currentInput.length() < 15) {
                currentInput += num;
            }
        }
        if (!operator.isEmpty() && !isOperatorPressed) {
            display.setText(formatNumber(firstNumber) + " " + operator + " " + currentInput);
        } else {
            updateDisplay();
        }
    }

    private void appendDecimal() {
        if (!currentInput.contains(".")) {
            if (currentInput.isEmpty()) {
                currentInput = "0.";
            } else {
                currentInput += ".";
            }
            if (!operator.isEmpty() && !isOperatorPressed) {
                display.setText(formatNumber(firstNumber) + " " + operator + " " + currentInput);
            } else {
                updateDisplay();
            }
        }
    }

    private void setOperator(String op) {
        if (!currentInput.isEmpty()) {
            firstNumber = Double.parseDouble(currentInput);
            operator = op;
            isOperatorPressed = true;
            isNewInput = true;
            display.setText(formatNumber(firstNumber) + " " + operator);
        } else if (firstNumber != 0) {
            operator = op;
            display.setText(formatNumber(firstNumber) + " " + operator);
        }
    }

    private void calculate() {
        if (operator.isEmpty()) {
            return;
        }

        if (currentInput.isEmpty() && !isOperatorPressed) {
            display.setText("Error: Enter number");
            return;
        }

        if (isOperatorPressed || currentInput.isEmpty()) {
            display.setText("Error: Enter number after operator");
            return;
        }

        double secondNumber = Double.parseDouble(currentInput);
        double result = 0;

        String historyEntry = formatNumber(firstNumber) + " " + operator + " " + formatNumber(secondNumber);

        switch (operator) {
            case "+":
                result = firstNumber + secondNumber;
                break;
            case "-":
                result = firstNumber - secondNumber;
                break;
            case "×":
                result = firstNumber * secondNumber;
                break;
            case "÷":
                if (secondNumber != 0) {
                    result = firstNumber / secondNumber;
                } else {
                    display.setText("Error: Division by zero");
                    return;
                }
                break;
            default:
                display.setText("Error: Invalid operator");
                return;
        }

        String resultStr = formatNumber(result);
        historyEntry += " = " + resultStr;
        historyList.add(historyEntry);

        String fullExpression = formatNumber(firstNumber) + " " + operator + " " + formatNumber(secondNumber) + " = " + resultStr;
        display.setText(fullExpression);

        currentInput = resultStr;
        operator = "";
        isOperatorPressed = false;
        isNewInput = true;
    }

    private String formatNumber(double num) {
        if (num == (long) num) {
            return String.valueOf((long) num);
        }
        String str = decimalFormat.format(num);
        return str;
    }

    private void clear() {
        currentInput = "";
        operator = "";
        firstNumber = 0;
        isNewInput = true;
        isOperatorPressed = false;
        updateDisplay();
    }

    private void backspace() {
        if (!currentInput.isEmpty()) {
            currentInput = currentInput.substring(0, currentInput.length() - 1);
            if (!operator.isEmpty() && !isOperatorPressed) {
                display.setText(formatNumber(firstNumber) + " " + operator + " " + currentInput);
            } else {
                updateDisplay();
            }
        }
    }

    private void updateDisplay() {
        if (currentInput.isEmpty()) {
            display.setText("0");
        } else {
            display.setText(currentInput);
        }
    }

    private void setupButtonAnimation(Button btn) {
        if (btn == null) return;
        btn.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate()
                            .scaleX(0.92f)
                            .scaleY(0.92f)
                            .setDuration(100)
                            .start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(100)
                            .start();
                    break;
            }
            return false;
        });
    }

    private void setupImageButtonAnimation(ImageButton btn) {
        if (btn == null) return;
        btn.setOnTouchListener((v, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    v.animate()
                            .scaleX(0.92f)
                            .scaleY(0.92f)
                            .setDuration(100)
                            .start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    v.animate()
                            .scaleX(1f)
                            .scaleY(1f)
                            .setDuration(100)
                            .start();
                    break;
            }
            return false;
        });
    }
}