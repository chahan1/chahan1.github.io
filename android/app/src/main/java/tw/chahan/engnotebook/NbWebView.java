package tw.chahan.engnotebook;

import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.net.Uri;
import android.view.ActionMode;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.webkit.WebView;

import org.json.JSONArray;

/**
 * 自訂選字選單：選取文字後出現「查單字」「加入單字」「翻譯」，並保留複製、全選（輸入框裡還有剪下、貼上）；
 * 其他項目（網路搜尋、分享、其他 App 的功能）拿掉，讓選單短一點。
 */
public class NbWebView extends WebView {
    private static final int ID_LOOKUP = 0x4e420001, ID_TRANSLATE = 0x4e420002, ID_ADD = 0x4e420003;

    public NbWebView(Context c) { super(c); }

    @Override
    public ActionMode startActionMode(ActionMode.Callback cb, int type) { return super.startActionMode(wrap(cb), type); }

    @Override
    public ActionMode startActionMode(ActionMode.Callback cb) { return super.startActionMode(wrap(cb)); }

    private static boolean keep(int id) {
        return id == ID_LOOKUP || id == ID_ADD || id == ID_TRANSLATE || id == android.R.id.copy || id == android.R.id.selectAll
                || id == android.R.id.cut || id == android.R.id.paste || id == android.R.id.pasteAsPlainText;
    }

    private static void tidy(Menu menu) {
        for (int i = menu.size() - 1; i >= 0; i--) {
            MenuItem it = menu.getItem(i);
            if (!keep(it.getItemId())) menu.removeItem(it.getItemId());
        }
        if (menu.findItem(ID_LOOKUP) == null)
            menu.add(Menu.NONE, ID_LOOKUP, 0, "查單字").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        if (menu.findItem(ID_ADD) == null)
            menu.add(Menu.NONE, ID_ADD, 1, "加入單字").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
        if (menu.findItem(ID_TRANSLATE) == null)
            menu.add(Menu.NONE, ID_TRANSLATE, 2, "翻譯").setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS);
    }

    private ActionMode.Callback wrap(ActionMode.Callback cb) {
        return new ActionMode.Callback2() {
            @Override public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                boolean r = cb.onCreateActionMode(mode, menu);
                tidy(menu);
                return r;
            }

            @Override public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                cb.onPrepareActionMode(mode, menu);
                tidy(menu);
                return true;
            }

            @Override public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                int id = item.getItemId();
                if (id != ID_LOOKUP && id != ID_ADD && id != ID_TRANSLATE) return cb.onActionItemClicked(mode, item);
                withSelection(text -> {
                    mode.finish();
                    if (text.isEmpty()) return;
                    if (id == ID_LOOKUP) callJs("nbLookup", text);
                    else if (id == ID_ADD) callJs("nbAddWord", text);
                    else translate(getContext(), text);
                });
                return true;
            }

            @Override public void onDestroyActionMode(ActionMode mode) { cb.onDestroyActionMode(mode); }

            @Override public void onGetContentRect(ActionMode mode, View view, Rect outRect) {
                if (cb instanceof ActionMode.Callback2) ((ActionMode.Callback2) cb).onGetContentRect(mode, view, outRect);
                else super.onGetContentRect(mode, view, outRect);
            }
        };
    }

    private interface TextCb { void run(String text); }

    /** 取得目前選取的文字（一般文字或輸入框裡的都可以） */
    private void withSelection(TextCb cb) {
        evaluateJavascript("(function(){var a=document.activeElement;"
                + "if(a&&(a.tagName==='INPUT'||a.tagName==='TEXTAREA')&&a.selectionEnd>a.selectionStart)return a.value.slice(a.selectionStart,a.selectionEnd);"
                + "var s=window.getSelection();return s?s.toString():'';})()", v -> {
            String t = "";
            try { t = new JSONArray("[" + v + "]").getString(0); } catch (Exception ignored) { }
            cb.run(t.replaceAll("\\s+", " ").trim());
        });
    }

    /** 呼叫網頁裡的函式（例如 nbLookup、nbAddWord），把選取的文字傳過去 */
    private void callJs(String fn, String text) {
        String q = new JSONArray().put(text).toString();   // 安全地把文字變成 JS 字串
        evaluateJavascript("(function(t){if(typeof window." + fn + "==='function')window." + fn + "(t);"
                + "else if(window.showToast)showToast('請先更新網頁版（關掉 App 再打開）',3000);})(" + q + "[0])", null);
    }

    /** 用 Google 翻譯 App 翻譯；沒有安裝就開翻譯網頁 */
    static void translate(Context c, String text) {
        Intent i = new Intent(Intent.ACTION_PROCESS_TEXT)
                .setType("text/plain")
                .putExtra(Intent.EXTRA_PROCESS_TEXT, text)
                .putExtra(Intent.EXTRA_PROCESS_TEXT_READONLY, true)
                .setPackage("com.google.android.apps.translate");
        try {
            c.startActivity(i);
        } catch (Exception e) {
            try {
                c.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(
                        "https://translate.google.com/?sl=auto&tl=zh-TW&op=translate&text=" + Uri.encode(text))));
            } catch (Exception ignored) { }
        }
    }
}
