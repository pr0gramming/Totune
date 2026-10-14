package com.totune.app;

import android.app.Activity;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.PopupMenu;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.List;

/** The processing chain as a vertical rack of module cards. */
public class RackView extends LinearLayout {
    public interface Host {
        void structureChanged();                        // order / add / remove / duplicate
        void paramChanged(Rack.Module m, int index, float value);
        void onChanged(Rack.Module m, boolean on);
        void configure(Rack.Module m);
        void addRequested();
    }

    private final Activity a;
    private Rack rack;
    private Host host;
    private final List<View> cards = new ArrayList<>();

    public RackView(Activity a) {
        super(a);
        this.a = a;
        setOrientation(VERTICAL);
        setClipChildren(false);
    }

    public void bind(Rack rack, Host host) {
        this.rack = rack;
        this.host = host;
        rebuild();
    }

    public void rebuild() {
        removeAllViews();
        cards.clear();
        int gap = Ui.dp(a, 8);
        for (int i = 0; i < rack.modules.size(); i++) {
            View c = card(rack.modules.get(i), i);
            cards.add(c);
            addView(c, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, 0, 0, gap));
        }
        if (rack.modules.isEmpty()) {
            TextView e = Ui.text(a, "The rack is empty — your voice plays untouched.", 13, Ui.DIM, false);
            e.setGravity(Gravity.CENTER);
            e.setPadding(0, Ui.dp(a, 14), 0, Ui.dp(a, 14));
            addView(e, Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        }
        TextView add = Ui.button(a, "＋  Add processing", false);
        add.setOnClickListener(v -> host.addRequested());
        addView(add, Ui.margins(Ui.lp(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT), 0, Ui.dp(a, 2), 0, Ui.dp(a, 8)));
    }

    private String subtitle(Rack.Module m) {
        ModuleType t = m.def();
        switch (t.id) {
            case ModuleType.TUNE: return ModuleType.STYLES[Math.round(m.p[0])] + " · " + ModuleType.RANGES[Math.round(m.p[6])];
            case ModuleType.HARMONY: return ModuleType.HARMONY_VOICES[Math.round(m.p[0])];
            case ModuleType.VOCODER: return ModuleType.VOCODER_MODES[Math.round(m.p[0])];
            case ModuleType.DELAY: return ModuleType.DELAY_DIVS[Math.round(m.p[1])] + (m.p[3] > 0.5f ? " · ping-pong" : "");
            case ModuleType.EQ: return (m.p[0] > 0.5f ? "Low cut " + Math.round(m.p[1]) + " Hz" : "No low cut") + (m.p[9] > 0.5f ? " · high cut" : "");
            case ModuleType.REVERB: return "Size " + Math.round(m.p[1]) + "% · " + String.format(java.util.Locale.US, "%.1f s", m.p[2]);
            default: return t.blurb;
        }
    }

    private View card(Rack.Module m, int index) {
        ModuleType t = m.def();
        LinearLayout card = Ui.row(a);
        card.setPadding(Ui.dp(a, 12), Ui.dp(a, 4), Ui.dp(a, 4), Ui.dp(a, 4));
        card.setBackground(Ui.round(Ui.PANEL, Ui.dp(a, 16), m.on ? 0xFF3A3F4B : Ui.LINE, Ui.dp(a, 1)));

        // power
        TextView power = Ui.text(a, m.on ? "●" : "○", 18, m.on ? Ui.TEAL : Ui.DIM, true);
        power.setPadding(0, Ui.dp(a, 8), Ui.dp(a, 10), Ui.dp(a, 8));
        card.addView(power);

        LinearLayout texts = Ui.column(a);
        TextView name = Ui.text(a, t.name, 15, m.on ? Ui.TEXT : Ui.DIM, true);
        TextView sub = Ui.text(a, subtitle(m), 12, Ui.DIM, false);
        sub.setSingleLine(true);
        sub.setEllipsize(android.text.TextUtils.TruncateAt.END);
        texts.addView(name);
        texts.addView(sub);
        card.addView(texts, Ui.weight(1));

        ModuleType.Param mp = t.params[t.main];
        Knob k = new Knob(a, mp.name, mp.min, mp.max, mp.def, m.p[t.main], mp.log, mp.step).format(mp::format);
        k.setAlpha(m.on ? 1f : 0.45f);
        k.listener(v -> {
            host.paramChanged(m, t.main, v);
            sub.setText(subtitle(m));
        });
        card.addView(k, Ui.lp(Ui.dp(a, 78), ViewGroup.LayoutParams.WRAP_CONTENT));

        LinearLayout side = Ui.column(a);
        side.setGravity(Gravity.CENTER);
        TextView cfg = Ui.text(a, "⚙", 20, Ui.DIM, false);
        cfg.setGravity(Gravity.CENTER);
        cfg.setPadding(Ui.dp(a, 10), Ui.dp(a, 8), Ui.dp(a, 10), Ui.dp(a, 8));
        cfg.setOnClickListener(v -> host.configure(m));
        TextView handle = Ui.text(a, "≡", 24, Ui.TEXT, true);
        handle.setGravity(Gravity.CENTER);
        handle.setPadding(Ui.dp(a, 10), Ui.dp(a, 4), Ui.dp(a, 10), Ui.dp(a, 8));
        side.addView(cfg);
        side.addView(handle);
        card.addView(side);

        View.OnClickListener toggle = v -> {
            m.on = !m.on;
            host.onChanged(m, m.on);
            power.setText(m.on ? "●" : "○");
            power.setTextColor(m.on ? Ui.TEAL : Ui.DIM);
            name.setTextColor(m.on ? Ui.TEXT : Ui.DIM);
            k.setAlpha(m.on ? 1f : 0.45f);
            card.setBackground(Ui.round(Ui.PANEL, Ui.dp(a, 16), m.on ? 0xFF3A3F4B : Ui.LINE, Ui.dp(a, 1)));
        };
        power.setOnClickListener(toggle);
        texts.setOnClickListener(v -> host.configure(m));

        handle.setOnTouchListener(new DragHandle(card, m, handle));
        return card;
    }

    /** ≡ handle: drag to reorder, tap for the menu. */
    private class DragHandle implements View.OnTouchListener {
        private final View card;
        private final Rack.Module m;
        private final View anchor;
        private float downY;
        private boolean dragging;
        private final int slop = ViewConfiguration.get(a).getScaledTouchSlop();

        DragHandle(View card, Rack.Module m, View anchor) { this.card = card; this.m = m; this.anchor = anchor; }

        @Override
        public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN:
                    downY = e.getRawY();
                    dragging = false;
                    v.getParent().requestDisallowInterceptTouchEvent(true);
                    return true;
                case MotionEvent.ACTION_MOVE: {
                    float dy = e.getRawY() - downY;
                    if (!dragging && Math.abs(dy) > slop) {
                        dragging = true;
                        card.setElevation(Ui.dp(a, 12));
                        card.setAlpha(0.92f);
                    }
                    if (dragging) {
                        card.setTranslationY(dy);
                        highlightTarget(targetIndex(dy));
                    }
                    return true;
                }
                case MotionEvent.ACTION_UP:
                case MotionEvent.ACTION_CANCEL: {
                    float dy = e.getRawY() - downY;
                    if (dragging) {
                        int from = rack.modules.indexOf(m), to = targetIndex(dy);
                        card.setTranslationY(0);
                        card.setElevation(0);
                        card.setAlpha(1f);
                        highlightTarget(-1);
                        if (from >= 0 && to != from) {
                            rack.move(from, to);
                            host.structureChanged();
                        }
                    } else if (e.getActionMasked() == MotionEvent.ACTION_UP) {
                        menu();
                    }
                    dragging = false;
                    return true;
                }
            }
            return false;
        }

        private int targetIndex(float dy) {
            int from = rack.modules.indexOf(m);
            float centre = card.getTop() + card.getHeight() / 2f + dy;
            int to = from;
            for (int i = 0; i < cards.size(); i++) {
                View c = cards.get(i);
                float mid = c.getTop() + c.getHeight() / 2f;
                if (i < from && centre < mid) { to = i; break; }
                if (i > from && centre > mid) to = i;
            }
            return to;
        }

        private void highlightTarget(int idx) {
            for (int i = 0; i < cards.size(); i++) {
                View c = cards.get(i);
                if (c == card) continue;
                c.setAlpha(idx >= 0 && i == idx ? 0.55f : 1f);
            }
        }

        private void menu() {
            PopupMenu pm = new PopupMenu(a, anchor);
            int idx = rack.modules.indexOf(m);
            if (idx > 0) pm.getMenu().add(0, 1, 0, "Move up");
            if (idx < rack.modules.size() - 1) pm.getMenu().add(0, 2, 1, "Move down");
            pm.getMenu().add(0, 3, 2, "Duplicate");
            pm.getMenu().add(0, 4, 3, m.on ? "Bypass" : "Turn on");
            pm.getMenu().add(0, 5, 4, "Settings…");
            pm.getMenu().add(0, 6, 5, "Remove");
            pm.setOnMenuItemClickListener(item -> {
                int i = rack.modules.indexOf(m);
                switch (item.getItemId()) {
                    case 1: rack.move(i, i - 1); host.structureChanged(); break;
                    case 2: rack.move(i, i + 1); host.structureChanged(); break;
                    case 3: rack.duplicate(i); host.structureChanged(); break;
                    case 4: m.on = !m.on; host.onChanged(m, m.on); rebuild(); break;
                    case 5: host.configure(m); break;
                    case 6: rack.modules.remove(m); host.structureChanged(); break;
                }
                return true;
            });
            pm.show();
        }
    }
}
