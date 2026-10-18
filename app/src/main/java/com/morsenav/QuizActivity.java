package com.morsenav;

import android.Manifest;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.widget.Button;
import android.widget.TextView;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.morsenav.ManeuverParser.Direction;
import com.morsenav.QuizStats.Code;
import com.morsenav.QuizStats.Cue;
import com.morsenav.QuizStats.Device;

import java.util.Random;

/**
 * Pattern quiz: plays a random cue in the chosen code on the chosen device, the rider names it, and
 * accuracy is kept per code and device. Results live in memory only.
 */
public class QuizActivity extends AppCompatActivity {

    /** Survives leaving and re-opening the screen, not an app restart. */
    private static final QuizStats STATS = new QuizStats(new Random());

    private Code code = Code.RHYTHM;
    private Device device = Device.WATCH;
    private Cue current;

    private Button btnCode;
    private Button btnDevice;
    private TextView feedback;
    private TextView stats;
    private final Button[] answers = new Button[6];
    private ActivityResultLauncher<String> bluetoothPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_quiz);
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.quizRoot), (v, insets) -> {
            Insets bars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom);
            return WindowInsetsCompat.CONSUMED;
        });

        btnCode = findViewById(R.id.btnQuizCode);
        btnDevice = findViewById(R.id.btnQuizDevice);
        feedback = findViewById(R.id.quizFeedback);
        stats = findViewById(R.id.quizStats);

        bluetoothPermission = registerForActivityResult(
                new ActivityResultContracts.RequestPermission(), granted -> {
                    if (granted) playNext();
                });

        btnCode.setOnClickListener(v -> {
            code = code == Code.RHYTHM ? Code.COUNT : Code.RHYTHM;
            current = null;
            refresh();
        });
        btnDevice.setOnClickListener(v -> {
            device = device == Device.WATCH ? Device.PHONE : Device.WATCH;
            current = null;
            refresh();
        });
        findViewById(R.id.btnQuizPlay).setOnClickListener(v -> {
            if (device == Device.WATCH && !hasBluetoothConnect()) {
                bluetoothPermission.launch(Manifest.permission.BLUETOOTH_CONNECT);
            } else {
                playNext();
            }
        });
        findViewById(R.id.btnQuizReset).setOnClickListener(v -> {
            STATS.reset();
            current = null;
            feedback.setText("");
            refresh();
        });

        bindAnswer(0, R.id.btnAnsLeft, new Cue(Direction.LEFT, false));
        bindAnswer(1, R.id.btnAnsLeftNow, new Cue(Direction.LEFT, true));
        bindAnswer(2, R.id.btnAnsRight, new Cue(Direction.RIGHT, false));
        bindAnswer(3, R.id.btnAnsRightNow, new Cue(Direction.RIGHT, true));
        bindAnswer(4, R.id.btnAnsUturn, new Cue(Direction.U_TURN, false));
        bindAnswer(5, R.id.btnAnsUturnNow, new Cue(Direction.U_TURN, true));
        findViewById(R.id.btnAnsMissed).setOnClickListener(v -> answer(null));

        refresh();
    }

    private boolean hasBluetoothConnect() {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU
                || ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
                == PackageManager.PERMISSION_GRANTED;
    }

    private void bindAnswer(int i, int id, Cue cue) {
        answers[i] = findViewById(id);
        answers[i].setOnClickListener(v -> answer(cue));
    }

    private void playNext() {
        current = STATS.randomCue(code);
        NavEngine.get(this).playQuiz(code == Code.RHYTHM, device == Device.WATCH, current.direction, current.imminent);
        feedback.setTextColor(ContextCompat.getColor(this, android.R.color.darker_gray));
        feedback.setText(R.string.quiz_playing);
        refresh();
    }

    private void answer(Cue answer) {
        if (current == null) return;
        STATS.record(code, device, current, answer);
        boolean right = current.equals(answer);
        feedback.setTextColor(right ? Color.rgb(46, 125, 50) : Color.rgb(198, 40, 40));
        feedback.setText(right ? getString(R.string.quiz_correct, current)
                : getString(R.string.quiz_wrong, current));
        DebugLog.add("QUIZ " + code + "/" + device + " played " + current + ", answered "
                + (answer != null ? answer : "missed"));
        current = null;
        refresh();
    }

    private void refresh() {
        btnCode.setText(code == Code.RHYTHM ? R.string.quiz_code_rhythm : R.string.quiz_code_count);
        btnDevice.setText(device == Device.WATCH ? R.string.quiz_device_watch : R.string.quiz_device_phone);
        for (Button b : answers) b.setEnabled(current != null);
        answers[5].setEnabled(current != null && code == Code.COUNT);  // the rhythm code has no U-turn now
        findViewById(R.id.btnAnsMissed).setEnabled(current != null);
        stats.setText(STATS.summary());
    }
}
