package com.totune.app;

import android.app.Activity;
import android.app.ProgressDialog;
import android.content.Intent;
import android.net.Uri;
import android.os.Bundle;
import android.text.InputType;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import com.totune.app.dsp.WavIO;

import java.util.List;

/** Home screen: list of takes + new recording / import. */
@SuppressWarnings("deprecation")
public class LibraryActivity extends Activity {
    private static final int REQ_BEAT = 10, REQ_IMPORT = 11;
    private LinearLayout list;

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        Ui.styleWindow(this);
        int pad = Ui.dp(this, 18);

        LinearLayout root = Ui.column(this);
        root.setPadding(pad, pad, pad, 0);

        TextView title = Ui.text(this, "Totune", 34, Ui.TEXT, true);
        root.addView(title);
        TextView sub = Ui.text(this, "Record. Tune. Mix.", 14, Ui.DIM, false);
        sub.setPadding(0, 0, 0, Ui.dp(this, 18));
        root.addView(sub);

        LinearLayout actions = Ui.row(this);
        TextView rec = Ui.button(this, "●  New recording", true);
        rec.setOnClickListener(v -> askBeat());
        TextView imp = Ui.button(this, "Import audio", false);
        imp.setOnClickListener(v -> pick(REQ_IMPORT));
        actions.addView(rec, Ui.margins(Ui.weight(1.3f), 0, 0, Ui.dp(this, 8), 0));
        actions.addView(imp, Ui.weight(1));
        root.addView(actions);

        root.addView(Ui.label(this, "Your takes"));

        ScrollView sv = new ScrollView(this);
        list = Ui.column(this);
        list.setPadding(0, 0, 0, pad);
        sv.addView(list);
        root.addView(sv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1));

        setContentView(root);
    }

    @Override
    protected void onResume() {
        super.onResume();
        refresh();
    }

    private void refresh() {
        list.removeAllViews();
        List<TakeStore.Take> takes = TakeStore.list(this);
        if (takes.isEmpty()) {
            TextView e = Ui.text(this, "No takes yet.\nTap “New recording”, sing something, then tune it.", 14, Ui.DIM, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, Ui.dp(this, 48), 0, 0);
            list.addView(e, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
            return;
        }
        java.text.DateFormat df = java.text.DateFormat.getDateTimeInstance(java.text.DateFormat.MEDIUM, java.text.DateFormat.SHORT);
        for (TakeStore.Take t : takes) {
            LinearLayout card = Ui.row(this);
            int p = Ui.dp(this, 14);
            card.setPadding(p, p, p, p);
            card.setBackground(Ui.ripple(Ui.round(Ui.PANEL, Ui.dp(this, 16), Ui.LINE, Ui.dp(this, 1))));
            LinearLayout texts = Ui.column(this);
            texts.addView(Ui.text(this, t.name, 16, Ui.TEXT, true));
            String meta = Ui.time(t.duration) + "  ·  " + df.format(new java.util.Date(t.created))
                    + (t.hasBeat() ? "  ·  with beat" : "");
            texts.addView(Ui.text(this, meta, 12.5f, Ui.DIM, false));
            card.addView(texts, Ui.weight(1));
            TextView more = Ui.text(this, "⋯", 22, Ui.DIM, true);
            more.setPadding(Ui.dp(this, 12), 0, Ui.dp(this, 4), 0);
            more.setOnClickListener(v -> takeMenu(t));
            card.addView(more);
            card.setOnClickListener(v -> open(t));
            card.setOnLongClickListener(v -> { takeMenu(t); return true; });
            list.addView(card, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT),
                    0, 0, 0, Ui.dp(this, 10)));
        }
    }

    private void open(TakeStore.Take t) {
        startActivity(new Intent(this, EditorActivity.class).putExtra("take", t.id()));
    }

    private void takeMenu(TakeStore.Take t) {
        Ui.dialog(this).setTitle(t.name)
                .setItems(new String[]{"Open", "Rename", "Delete"}, (d, which) -> {
                    if (which == 0) open(t);
                    else if (which == 1) rename(t);
                    else Ui.dialog(this).setTitle("Delete “" + t.name + "”?")
                                .setMessage("This can't be undone.")
                                .setPositiveButton("Delete", (d2, w2) -> { TakeStore.delete(t); refresh(); })
                                .setNegativeButton("Cancel", null).show();
                }).show();
    }

    private void rename(TakeStore.Take t) {
        EditText e = new EditText(this);
        e.setText(t.name);
        e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        e.setSelectAllOnFocus(true);
        LinearLayout wrap = Ui.column(this);
        int p = Ui.dp(this, 20);
        wrap.setPadding(p, Ui.dp(this, 8), p, 0);
        wrap.addView(e);
        Ui.dialog(this).setTitle("Rename take").setView(wrap)
                .setPositiveButton("Save", (d, w) -> {
                    String n = e.getText().toString().trim();
                    if (!n.isEmpty()) { t.name = n; TakeStore.save(t); refresh(); }
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void askBeat() {
        Ui.dialog(this).setTitle("New recording")
                .setItems(new String[]{"Vocals only", "Sing over a beat…"}, (d, which) -> {
                    if (which == 0) startActivity(new Intent(this, RecordActivity.class));
                    else pick(REQ_BEAT);
                }).show();
    }

    private void pick(int req) {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("audio/*");
        try {
            startActivityForResult(i, req);
        } catch (Exception e) {
            Toast.makeText(this, "No file picker available", Toast.LENGTH_LONG).show();
        }
    }

    @Override
    protected void onActivityResult(int req, int res, Intent data) {
        super.onActivityResult(req, res, data);
        if (res != RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        if (req == REQ_BEAT) {
            Intent i = new Intent(this, RecordActivity.class);
            i.setData(uri);
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(i);
        } else if (req == REQ_IMPORT) {
            importVocal(uri);
        }
    }

    private void importVocal(Uri uri) {
        ProgressDialog pd = new ProgressDialog(this, android.R.style.Theme_Material_Dialog_Alert);
        pd.setMessage("Importing audio…");
        pd.setCancelable(false);
        pd.show();
        new Thread(() -> {
            try {
                float[][] st = AudioCodec.decodeStereo(this, uri, 600);
                float[] mono = new float[st[0].length];
                for (int i = 0; i < mono.length; i++) mono[i] = 0.5f * (st[0][i] + st[1][i]);
                TakeStore.Take t = TakeStore.create(this);
                t.name = "Imported " + (TakeStore.list(this).size() + 1);
                WavIO.write(t.vocal(), new float[][]{mono}, AudioCodec.SR);
                t.duration = mono.length / (double) AudioCodec.SR;
                TakeStore.save(t);
                runOnUiThread(() -> { pd.dismiss(); open(t); });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    pd.dismiss();
                    Toast.makeText(this, "Couldn't import: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        }).start();
    }
}
