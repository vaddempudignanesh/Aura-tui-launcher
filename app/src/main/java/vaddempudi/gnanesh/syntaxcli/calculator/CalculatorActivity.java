package vaddempudi.gnanesh.syntaxcli.calculator;

import android.content.SharedPreferences;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.List;

import vaddempudi.gnanesh.syntaxcli.R;

public class CalculatorActivity extends AppCompatActivity {

    private static final String PREFS = "ohi_calc_store";
    private static final String KEY_HISTORY = "history";
    private static final int MAX_HISTORY = 500;

    // ── Engine state ──
    private String currentInput = "";     // digits typed for the *current* operand
    private String pendingOp = "";        // + - × ÷
    private double accumulator = 0;       // running total
    private boolean hasAccumulator = false;
    private boolean justEvaluated = false; // last action was "="
    private boolean operatorJustPressed = false;

    private final DecimalFormat fmt = new DecimalFormat("#.##########");

    // ── Views ──
    private TextView display, expression;
    private LinearLayout historyPanel;
    private ScrollView historyScroll;
    private TextView historyEmpty;

    // ── History ──
    private final List<String> history = new ArrayList<>();
    private SharedPreferences prefs;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_calculator);

        prefs = getSharedPreferences(PREFS, MODE_PRIVATE);
        loadHistory();

        display = findViewById(R.id.calc_display);
        expression = findViewById(R.id.calc_expression);
        historyPanel = findViewById(R.id.calc_history_panel);
        historyScroll = findViewById(R.id.calc_history_scroll);
        historyEmpty = findViewById(R.id.calc_history_empty);

        setupButtons();
        refreshDisplay();
        refreshHistory();
    }

    // ═══════════════════ History persistence ═══════════════════

    private void loadHistory() {
        history.clear();
        String raw = prefs.getString(KEY_HISTORY, "");
        if (raw == null || raw.isEmpty()) return;
        for (String line : raw.split("\n")) {
            if (!line.isEmpty()) history.add(line);
        }
    }

    private void saveHistory() {
        // Trim to MAX_HISTORY most recent
        while (history.size() > MAX_HISTORY) history.remove(0);
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < history.size(); i++) {
            if (i > 0) sb.append('\n');
            sb.append(history.get(i));
        }
        prefs.edit().putString(KEY_HISTORY, sb.toString()).apply();
    }

    private void addHistory(String entry) {
        history.add(entry);
        saveHistory();
        refreshHistory();
    }

    private void clearHistory() {
        history.clear();
        saveHistory();
        refreshHistory();
    }

    private void refreshHistory() {
        historyPanel.removeAllViews();

        if (history.isEmpty()) {
            historyEmpty.setVisibility(View.VISIBLE);
            return;
        }
        historyEmpty.setVisibility(View.GONE);

        // newest at the bottom, so the ScrollView auto-scrolls to show it
        for (String line : history) {
            TextView tv = new TextView(this);
            tv.setText(line);
            tv.setTextColor(0xFF00FF00);
            tv.setTextSize(13);
            tv.setTypeface(android.graphics.Typeface.MONOSPACE);
            tv.setPadding(0, dp(6), 0, dp(6));
            tv.setBackgroundColor(0x00000000);
            historyPanel.addView(tv);
        }

        historyScroll.post(() -> historyScroll.fullScroll(View.FOCUS_DOWN));
    }

    private void setupButtons() {
        int[] numberIds = {
                R.id.btn_0, R.id.btn_1, R.id.btn_2, R.id.btn_3, R.id.btn_4,
                R.id.btn_5, R.id.btn_6, R.id.btn_7, R.id.btn_8, R.id.btn_9
        };
        for (int id : numberIds) {
            View b = findViewById(id);
            b.setOnClickListener(v -> {
                CharSequence t = ((TextView) v).getText();
                appendNumber(t.toString());
            });
            animate(b);
        }

        bind(R.id.btn_plus,     v -> setOperator("+"));
        bind(R.id.btn_minus,    v -> setOperator("-"));
        bind(R.id.btn_multiply, v -> setOperator("×"));
        bind(R.id.btn_divide,   v -> setOperator("÷"));
        bind(R.id.btn_equals,   v -> calculate());
        bind(R.id.btn_clear,    v -> clearAll());
        bind(R.id.btn_decimal,  v -> appendDecimal());
        bind(R.id.btn_backspace,v -> backspace());
        bind(R.id.btn_history,  v -> toggleHistory());

        View clearHist = findViewById(R.id.btn_clear_history);
        if (clearHist != null) {
            clearHist.setOnClickListener(v -> clearHistory());
            animate(clearHist);
        }

        View back = findViewById(R.id.btn_back);
        if (back != null) {
            back.setOnClickListener(v -> finish());
            animate(back);
        }
    }

    private void bind(int id, View.OnClickListener l) {
        View v = findViewById(id);
        if (v == null) return;
        v.setOnClickListener(l);
        animate(v);
    }



    // ═══════════════════ Core logic ═══════════════════

    private void appendNumber(String num) {
        if (justEvaluated) {
            // starting a fresh calculation after "="
            currentInput = "";
            pendingOp = "";
            accumulator = 0;
            hasAccumulator = false;
            justEvaluated = false;
        }
        if (operatorJustPressed) {
            currentInput = "";
            operatorJustPressed = false;
        }
        if (currentInput.length() >= 15) return;

        if (currentInput.equals("0")) currentInput = num;
        else currentInput += num;

        refreshDisplay();
    }

    private void appendDecimal() {
        if (justEvaluated) {
            currentInput = "";
            pendingOp = "";
            accumulator = 0;
            hasAccumulator = false;
            justEvaluated = false;
        }
        if (operatorJustPressed) {
            currentInput = "";
            operatorJustPressed = false;
        }
        if (currentInput.contains(".")) return;
        currentInput = currentInput.isEmpty() ? "0." : currentInput + ".";
        refreshDisplay();
    }

    private void setOperator(String op) {
        if (justEvaluated) {
            // chain from previous result: 2+3= then + → accumulator=5
            accumulator = parse(currentInput);
            hasAccumulator = true;
            pendingOp = op;
            operatorJustPressed = true;
            currentInput = "";
            justEvaluated = false;
            refreshDisplay();
            return;
        }

        if (!currentInput.isEmpty()) {
            double operand = parse(currentInput);
            if (hasAccumulator && !pendingOp.isEmpty()) {
                accumulator = apply(accumulator, operand, pendingOp);
            } else {
                accumulator = operand;
                hasAccumulator = true;
            }
            pendingOp = op;
            currentInput = "";
            operatorJustPressed = true;
        } else if (hasAccumulator) {
            // user just presses another operator — swap
            pendingOp = op;
            operatorJustPressed = true;
        }
        refreshDisplay();
    }

    private void calculate() {
        if (!hasAccumulator || pendingOp.isEmpty()) {
            // "=" with no pending op → just re-display
            return;
        }

        // If operator was just pressed and nothing typed, use accumulator itself
        double operand = currentInput.isEmpty() ? accumulator : parse(currentInput);
        double result = apply(accumulator, operand, pendingOp);

        String opStr = pendingOp;
        String entry = format(accumulator) + " " + opStr + " "
                + format(operand) + " = " + format(result);
        addHistory(entry);

        accumulator = result;
        hasAccumulator = true;
        currentInput = format(result);
        pendingOp = "";
        operatorJustPressed = false;
        justEvaluated = true;
        refreshDisplay();
    }

    private double apply(double a, double b, String op) {
        switch (op) {
            case "+": return a + b;
            case "-": return a - b;
            case "×": return a * b;
            case "÷": return b == 0 ? Double.NaN : a / b;
            default:  return b;
        }
    }

    private void clearAll() {
        currentInput = "";
        pendingOp = "";
        accumulator = 0;
        hasAccumulator = false;
        justEvaluated = false;
        operatorJustPressed = false;
        refreshDisplay();
    }

    private void backspace() {
        if (justEvaluated) {
            // treat backspace after = as starting a new input
            currentInput = "";
            justEvaluated = false;
        }
        if (currentInput.isEmpty()) return;
        currentInput = currentInput.substring(0, currentInput.length() - 1);
        refreshDisplay();
    }

    private void toggleHistory() {
        if (historyPanel.getVisibility() == View.VISIBLE) {
            historyPanel.setVisibility(View.GONE);
        } else {
            historyPanel.setVisibility(View.VISIBLE);
            refreshHistory();
        }
    }

    // ═══════════════════ Display ═══════════════════

    private void refreshDisplay() {
        if (currentInput.isEmpty()) {
            display.setText(hasAccumulator ? format(accumulator) : "0");
        } else {
            display.setText(currentInput);
        }

        // secondary expression line
        StringBuilder sb = new StringBuilder();
        if (hasAccumulator && !pendingOp.isEmpty()) {
            sb.append(format(accumulator)).append(" ").append(pendingOp);
            if (!currentInput.isEmpty() && !operatorJustPressed) {
                sb.append(" ").append(currentInput);
            }
        } else if (justEvaluated) {
            sb.append(""); // result already in the main display
        }
        expression.setText(sb.toString());
    }

    private double parse(String s) {
        try { return Double.parseDouble(s); }
        catch (Exception e) { return 0; }
    }

    private String format(double d) {
        if (Double.isNaN(d)) return "Error";
        if (Double.isInfinite(d)) return "Error";
        if (d == (long) d) return String.valueOf((long) d);
        String s = fmt.format(d);
        if (s.startsWith(".")) s = "0" + s;
        return s;
    }

    // ═══════════════════ Visual feedback ═══════════════════

    private void animate(View v) {
        v.setOnTouchListener((view, event) -> {
            switch (event.getAction()) {
                case MotionEvent.ACTION_DOWN:
                    view.animate().scaleX(0.94f).scaleY(0.94f)
                            .setDuration(90).start();
                    break;
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL:
                    view.animate().scaleX(1f).scaleY(1f)
                            .setDuration(90).start();
                    break;
            }
            return false;
        });
    }

    private int dp(int v) {
        return Math.round(v * getResources().getDisplayMetrics().density);
    }
}