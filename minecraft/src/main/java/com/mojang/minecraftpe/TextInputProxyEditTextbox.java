package com.mojang.minecraftpe;

import android.content.Context;
import android.text.*;
import android.text.method.ArrowKeyMovementMethod;
import android.text.method.MetaKeyKeyListener;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.view.inputmethod.InputConnection;
import android.view.inputmethod.InputConnectionWrapper;
import android.widget.TextView;

import androidx.appcompat.widget.AppCompatEditText;

import java.util.ArrayList;
import java.util.List;

public class TextInputProxyEditTextbox extends AppCompatEditText {

    private MCPEKeyWatcher mcpeKeyWatcher;
    private int allowedLength;


    public interface MCPEKeyWatcher {
        boolean onBackKeyPressed();
        void onDeleteKeyPressed();
        void updateShiftKeyState(int state);
    }

    public TextInputProxyEditTextbox(Context context) {
        super(context);
        setMovementMethod(new LogicalLineMovementMethod());
    }


    @Override
    protected void onSelectionChanged(int selStart, int selEnd) {
        super.onSelectionChanged(selStart, selEnd);

        if (getMovementMethod() instanceof LogicalLineMovementMethod) {
            ((LogicalLineMovementMethod) getMovementMethod()).onSelectionChanged();
        }
    }


    public void updateFilters(int maxLength, boolean singleLine) {
        allowedLength = maxLength;

        List<InputFilter> filters = new ArrayList<>();

        if (maxLength > 0) {
            filters.add(new InputFilter.LengthFilter(maxLength));
        }

        if (singleLine) {
            filters.add(createSingleLineFilter());
        }

        filters.add(createUnicodeSpaceFilter());

        setFilters(filters.toArray(new InputFilter[0]));
    }



    @Override
    public InputConnection onCreateInputConnection(EditorInfo outAttrs) {
        return new MCPEInputConnection(
                super.onCreateInputConnection(outAttrs),
                true,
                this
        );
    }



    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        if (onKeyShortcut(keyCode, event)) {
            return true;
        }
        return super.onKeyDown(keyCode, event);
    }

    @Override
    public boolean onKeyPreIme(int keyCode, KeyEvent event) {

        if (mcpeKeyWatcher != null) {

            if (keyCode == KeyEvent.KEYCODE_BACK
                    && event.getAction() == KeyEvent.ACTION_UP) {

                return mcpeKeyWatcher.onBackKeyPressed();
            }

            mcpeKeyWatcher.updateShiftKeyState(
                    event.isShiftPressed() ? 1 : 0
            );
        }

        return super.onKeyPreIme(keyCode, event);
    }

    public void setOnMCPEKeyWatcher(MCPEKeyWatcher watcher) {
        this.mcpeKeyWatcher = watcher;
    }



    private InputFilter createSingleLineFilter() {
        return (source, start, end, dest, dstart, dend) -> {
            for (int i = start; i < end; i++) {
                if (source.charAt(i) == '\n') {
                    return source.subSequence(start, i);
                }
            }
            return null;
        };
    }

    /**
     * Converts full-width space (U+3000) into normal space
     */
    private InputFilter createUnicodeSpaceFilter() {
        return (source, start, end, dest, dstart, dend) -> {

            StringBuilder builder = null;

            for (int i = start; i < end; i++) {
                if (source.charAt(i) == 12288) { // U+3000
                    if (builder == null) {
                        builder = new StringBuilder(source);
                    }
                    builder.setCharAt(i, ' ');
                }
            }

            return builder != null
                    ? builder.subSequence(start, end)
                    : null;
        };
    }



    private class MCPEInputConnection extends InputConnectionWrapper {

        private final TextInputProxyEditTextbox textbox;

        MCPEInputConnection(InputConnection target,
                            boolean mutable,
                            TextInputProxyEditTextbox box) {
            super(target, mutable);
            this.textbox = box;
        }

        @Override
        public boolean sendKeyEvent(KeyEvent event) {

            if (textbox.getText().length() == 0
                    && event.getAction() == KeyEvent.ACTION_DOWN
                    && event.getKeyCode() == KeyEvent.KEYCODE_DEL) {

                if (mcpeKeyWatcher != null) {
                    mcpeKeyWatcher.onDeleteKeyPressed();
                }
                return false;
            }

            return super.sendKeyEvent(event);
        }
    }



    private static class LogicalLineMovementMethod
            extends ArrowKeyMovementMethod {

        private int idealColumn = -1;
        private boolean navigatingVertically = false;

        void onSelectionChanged() {
            if (!navigatingVertically) {
                idealColumn = -1;
            }
        }

        private int getLineStart(CharSequence text, int pos) {
            int prev = TextUtils.lastIndexOf(text, '\n', pos - 1);
            return prev >= 0 ? prev + 1 : 0;
        }

        private int getLineEnd(CharSequence text, int pos) {
            int next = TextUtils.indexOf(text, '\n', pos);
            return next >= 0 ? next : text.length();
        }

        private int getColumn(CharSequence text, int pos) {
            return pos - getLineStart(text, pos);
        }

        private int ensureIdealColumn(CharSequence text, int pos) {
            if (idealColumn < 0) {
                idealColumn = getColumn(text, pos);
            }
            return idealColumn;
        }

        private void move(Spannable s, int pos) {
            if (isSelecting(s)) {
                Selection.extendSelection(s, pos);
            } else {
                Selection.setSelection(s, pos);
            }
        }

        static boolean isSelecting(Spannable s) {
            return MetaKeyKeyListener.getMetaState(s, MetaKeyKeyListener.META_SHIFT_ON) != 0;
        }

        @Override
        protected boolean up(TextView view, Spannable text) {

            int cur = Selection.getSelectionEnd(text);
            int start = getLineStart(text, cur);

            if (start == 0) return false;

            int prevLineEnd = start - 1;
            int prevStart = getLineStart(text, prevLineEnd);

            int target = prevStart +
                    Math.min(
                            ensureIdealColumn(text, cur),
                            prevLineEnd - prevStart
                    );

            navigatingVertically = true;
            move(text, target);
            navigatingVertically = false;

            return true;
        }

        @Override
        protected boolean down(TextView view, Spannable text) {

            int cur = Selection.getSelectionEnd(text);
            int end = getLineEnd(text, cur);

            if (end >= text.length()) return false;

            int nextStart = end + 1;
            int nextEnd = getLineEnd(text, nextStart);

            int target = nextStart +
                    Math.min(
                            ensureIdealColumn(text, cur),
                            nextEnd - nextStart
                    );

            navigatingVertically = true;
            move(text, target);
            navigatingVertically = false;

            return true;
        }
    }
}
