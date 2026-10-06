package vaddempudi.gnanesh.syntaxcli.ime;

import android.inputmethodservice.InputMethodService;
import android.view.KeyEvent;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;

public class BasicImeService extends InputMethodService {

    private BasicKeyboardView keyboardView;

    @Override
    public View onCreateInputView() {
        keyboardView = new BasicKeyboardView(this);
        keyboardView.setListener(new BasicKeyboardView.Listener() {
            @Override
            public void onKey(int code, String text) {
                handleKey(code, text);
            }
        });
        return keyboardView;
    }

    @Override
    public void onStartInputView(EditorInfo info, boolean restarting) {
        super.onStartInputView(info, restarting);
        setPage(0);
    }

    @Override
    public void onFinishInputView(boolean finishingInput) {
        super.onFinishInputView(finishingInput);
        setPage(0);
    }

    @Override
    public boolean onEvaluateFullscreenMode() {
        return false;
    }

    private void setPage(int page) {
        if (keyboardView == null) return;
        keyboardView.setPage(page);
    }

    private void handleKey(int code, String text) {
        InputConnection ic = getCurrentInputConnection();
        if (ic == null) return;

        switch (code) {
            case BasicKey.CODE_SHIFT: {
                if (keyboardView == null) return;

                boolean second = keyboardView.registerShiftTap();

                if (keyboardView.isShiftLocked()) {
                    keyboardView.clearShiftLock();
                    return;
                }

                if (second) {
                    keyboardView.setShiftLocked(true);
                    keyboardView.setShifted(false);
                    return;
                }

                keyboardView.setShifted(!keyboardView.isShifted());
                return;
            }

            case BasicKey.CODE_SYMBOLS:
                setPage(1);
                return;

            case BasicKey.CODE_SYMBOLS_2:
                setPage(2);
                return;

            case BasicKey.CODE_SYM_PAGE_1:
                setPage(1);
                return;

            case BasicKey.CODE_SYM_PAGE_2:
                setPage(2);
                return;

            case BasicKey.CODE_SYM_PAGE_3:
                setPage(3);
                return;

            case BasicKey.CODE_SYM_PAGE_4:
                setPage(3);
                return;

            case BasicKey.CODE_ABC:
                setPage(0);
                return;

            case BasicKey.CODE_DELETE:
                CharSequence before = ic.getTextBeforeCursor(1, 0);
                if (before != null && before.length() > 0) {
                    ic.deleteSurroundingText(1, 0);
                }
                return;

            case BasicKey.CODE_ENTER:
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_ENTER));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_ENTER));
                return;

            case BasicKey.CODE_SPACE:
                ic.commitText(" ", 1);
                if (keyboardView != null
                        && keyboardView.isShifted()
                        && !keyboardView.isShiftLocked()) {
                    keyboardView.clearManualShift();
                }
                return;

            case BasicKey.CODE_LEFT:
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_LEFT));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_LEFT));
                return;

            case BasicKey.CODE_RIGHT:
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_RIGHT));
                ic.sendKeyEvent(new KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_RIGHT));
                return;
        }

        if (text != null && text.length() > 0) {
            ic.commitText(text, 1);

            if (keyboardView != null
                    && !keyboardView.isSymbols()
                    && !keyboardView.isSymbols2()
                    && !keyboardView.isSymbols3()) {
                if (keyboardView.isShiftLocked()) {
                    keyboardView.setShifted(true);
                } else if (keyboardView.isShifted()) {
                    keyboardView.clearManualShift();
                }
            }
        }
    }
}