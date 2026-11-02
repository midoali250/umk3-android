package com.midoali.atari;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.FrameLayout;
import android.widget.ImageButton;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import com.midoali.atari.core.MoveData;
import java.util.List;
import java.util.Locale;

/**
 * Overlay opened by the FINISH button: every fatality and special move of one fighter with
 * a single tap per move. The game keeps running behind the panel because a fatality has to
 * be entered while "FINISH HIM" is still on screen.
 */
final class MovePanel extends FrameLayout {
    interface Listener {
        void onMoveChosen(MoveData.Move move, String fighter, boolean mirror);
        void onFighterChanged(String fighter, boolean mirror);
    }

    private final Activity activity;
    private final Listener listener;
    private final LinearLayout list;
    private final TextView subtitle;
    private final Spinner fighter;
    private final CheckBox mirror;
    private boolean built;

    MovePanel(Activity activity, Listener listener, String fighterName, boolean mirrored) {
        super(activity);
        this.activity = activity;
        this.listener = listener;
        setBackgroundColor(0xe6101216);
        setClickable(true);
        setVisibility(GONE);

        LinearLayout card = new LinearLayout(activity);
        card.setOrientation(LinearLayout.VERTICAL);
        GradientDrawable background = new GradientDrawable();
        background.setColor(0xff171b22);
        background.setCornerRadius(dp(16));
        background.setStroke(dp(1), 0x50ffffff);
        card.setBackground(background);
        card.setPadding(dp(14), dp(12), dp(14), dp(10));

        LinearLayout header = new LinearLayout(activity);
        header.setGravity(Gravity.CENTER_VERTICAL);
        TextView title = new TextView(activity);
        title.setText(activity.getString(R.string.move_panel_title));
        title.setTextColor(Color.WHITE);
        title.setTextSize(16);
        header.addView(title, new LinearLayout.LayoutParams(0, -2, 1));
        ImageButton close = new ImageButton(activity);
        close.setImageResource(R.drawable.ic_close);
        close.setColorFilter(Color.WHITE);
        close.setBackgroundColor(0x00000000);
        close.setContentDescription(activity.getString(R.string.menu_close));
        close.setOnClickListener(v -> hide());
        header.addView(close, new LinearLayout.LayoutParams(dp(38), dp(38)));
        card.addView(header, new LinearLayout.LayoutParams(-1, -2));

        subtitle = new TextView(activity);
        subtitle.setText(activity.getString(R.string.move_panel_hint));
        subtitle.setTextColor(0xff9fb3c8);
        subtitle.setTextSize(11);
        card.addView(subtitle);

        fighter = new Spinner(activity);
        ArrayAdapter<String> adapter = new ArrayAdapter<>(activity,
                android.R.layout.simple_spinner_item, MoveData.CHARACTER_NAMES);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        fighter.setAdapter(adapter);
        fighter.setSelection(MoveData.characterIndex(fighterName));
        card.addView(fighter, new LinearLayout.LayoutParams(-1, dp(46)));

        mirror = new CheckBox(activity);
        mirror.setText(activity.getString(R.string.move_mirror));
        mirror.setChecked(mirrored);
        mirror.setTextColor(Color.WHITE);
        mirror.setTextSize(12);
        card.addView(mirror);

        ScrollView scroll = new ScrollView(activity);
        list = new LinearLayout(activity);
        list.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(list);
        card.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // Docked to the right edge so the fighters stay visible: a fatality has to be typed
        // while "FINISH HIM" is on screen, so the panel must not hide the fight.
        FrameLayout.LayoutParams cardParams = new FrameLayout.LayoutParams(
                Math.min(dp(400), (int)(activity.getResources().getDisplayMetrics().widthPixels * 0.54f)),
                -1, Gravity.END);
        cardParams.topMargin = dp(8); cardParams.bottomMargin = dp(8); cardParams.rightMargin = dp(6);
        addView(card, cardParams);

        fighter.setOnItemSelectedListener(new android.widget.AdapterView.OnItemSelectedListener() {
            @Override public void onItemSelected(android.widget.AdapterView<?> parent, View view, int position, long id) {
                if (!built) return;
                rebuild(MoveData.CHARACTER_NAMES[position]);
                listener.onFighterChanged(MoveData.CHARACTER_NAMES[position], mirror.isChecked());
            }
            @Override public void onNothingSelected(android.widget.AdapterView<?> parent) { }
        });
        mirror.setOnCheckedChangeListener((v, checked) ->
                listener.onFighterChanged(selectedFighter(), checked));
    }

    String selectedFighter() { return MoveData.CHARACTER_NAMES[Math.max(0, fighter.getSelectedItemPosition())]; }
    boolean mirrored() { return mirror.isChecked(); }
    LinearLayout listForTest() { return list; }

    /** Test hook: switch fighter synchronously and rebuild the rows. */
    void selectFighterForTest(String name) {
        int index = MoveData.characterIndex(name);
        fighter.setSelection(index);
        rebuild(MoveData.CHARACTER_NAMES[index]);
    }

    /** Test hook: find the row button of a move so the test can click it like a user. */
    Button findRowForTest(String name) {
        for (int i = 0; i < list.getChildCount(); ++i) {
            View child = list.getChildAt(i);
            if (child instanceof Button && name.equals(String.valueOf(child.getTag()))) {
                return (Button) child;
            }
        }
        return null;
    }

    void show(String fighterName, boolean mirrored) {
        fighter.setSelection(MoveData.characterIndex(fighterName));
        mirror.setChecked(mirrored);
        rebuild(MoveData.CHARACTER_NAMES[Math.max(0, fighter.getSelectedItemPosition())]);
        setVisibility(VISIBLE);
    }

    void hide() { setVisibility(GONE); }

    boolean isPanelOpen() { return getVisibility() == VISIBLE; }

    private void rebuild(String fighterName) {
        built = true;
        list.removeAllViews();
        List<MoveData.Move> moves = MoveData.movesFor(fighterName);
        int section = -1;
        for (MoveData.Move move : moves) {
            if (move.category != section) {
                section = move.category;
                TextView header = new TextView(activity);
                header.setText(sectionTitle(section));
                header.setTextColor(0xffffd66e);
                header.setTextSize(13);
                header.setPadding(0, dp(8), 0, dp(2));
                list.addView(header);
            }
            list.addView(row(move), new LinearLayout.LayoutParams(-1, dp(50)));
        }
        if (moves.isEmpty()) {
            TextView empty = new TextView(activity);
            empty.setText(activity.getString(R.string.move_panel_empty));
            empty.setTextColor(0xff9fb3c8);
            list.addView(empty);
        }
        subtitle.setText(activity.getString(R.string.move_panel_hint));
    }

    private String sectionTitle(int category) {
        if (category == MoveData.CATEGORY_FATALITY) return activity.getString(R.string.section_finishers);
        if (category == MoveData.CATEGORY_EXTRA) return activity.getString(R.string.section_extras);
        return activity.getString(R.string.section_specials);
    }

    private Button row(MoveData.Move move) {
        Button button = new Button(activity);
        button.setAllCaps(false);
        button.setTextSize(11);
        button.setSingleLine(false);
        button.setMaxLines(2);
        button.setEllipsize(null);
        button.setTextColor(Color.WHITE);
        GradientDrawable background = new GradientDrawable();
        background.setColor(move.category == MoveData.CATEGORY_FATALITY ? 0xff5c1f28 : 0xff23303d);
        background.setCornerRadius(dp(8));
        button.setBackground(background);
        button.setText(label(move));
        button.setTag(move.name);
        button.setOnClickListener(v -> {
            hide();
            listener.onMoveChosen(move, selectedFighter(), mirror.isChecked());
        });
        return button;
    }

    private String label(MoveData.Move move) {
        StringBuilder text = new StringBuilder();
        text.append(move.name);
        if (!move.displayName().equalsIgnoreCase(move.name)) {
            text.append(" · ").append(move.displayName());
        }
        if (move.category == MoveData.CATEGORY_FATALITY) {
            text.append(" · ").append(distanceLabel(move.distance));
        }
        String sequence = humanSequence(move);
        if (!sequence.isEmpty()) text.append('\n').append(sequence);
        return text.toString();
    }

    /** Readable form of the scripted input, in the same letters printed on the pad. */
    static String humanSequence(MoveData.Move move) {
        StringBuilder text = new StringBuilder();
        if (move.held != null && !move.held.isEmpty()) {
            text.append(heldLabel(move.held)).append(" + ");
        }
        String[] steps = move.sequence.trim().split("\\s+");
        for (int i = 0; i < steps.length; ++i) {
            if (i > 0) text.append(", ");
            String step = steps[i];
            for (int c = 0; c < step.length(); ++c) {
                if (c > 0) text.append('+');
                text.append(tokenLabel(step.charAt(c)));
            }
        }
        return text.toString();
    }

    private static String heldLabel(String held) {
        StringBuilder text = new StringBuilder();
        for (int i = 0; i < held.length(); ++i) {
            char token = held.charAt(i);
            if (token == '+' || Character.isWhitespace(token)) continue;
            if (text.length() > 0) text.append('+');
            text.append(tokenLabel(token));
        }
        return text.toString();
    }

    private static String tokenLabel(char token) {
        switch (token) {
            case 'U': return "↑";
            case 'D': return "↓";
            case 'L': return "←";
            case 'R': return "→";
            default: return String.valueOf(Character.toUpperCase(token));
        }
    }

    private String distanceLabel(String distance) {
        switch (distance == null ? "" : distance.toUpperCase(Locale.ROOT)) {
            case "CLOSE": return activity.getString(R.string.distance_close);
            case "SWEEP": return activity.getString(R.string.distance_sweep);
            case "FAR": return activity.getString(R.string.distance_far);
            default: return activity.getString(R.string.distance_anywhere);
        }
    }

    private int dp(float value) { return Math.round(value * activity.getResources().getDisplayMetrics().density); }

    /** Lays the list out with a plain measure/layout pass (used for offscreen panel sizing). */
    void layoutForTest(int width, int height) {
        measure(MeasureSpec.makeMeasureSpec(width, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(height, MeasureSpec.EXACTLY));
        layout(0, 0, width, height);
    }
}
