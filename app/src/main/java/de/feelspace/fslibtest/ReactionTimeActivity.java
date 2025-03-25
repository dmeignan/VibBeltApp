package de.feelspace.fslibtest;

import android.os.Bundle;
import android.os.Handler;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;

import androidx.appcompat.app.AppCompatActivity;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import de.feelspace.fslib.BeltCommandInterface;
import de.feelspace.fslib.BeltMode;
import de.feelspace.fslib.BeltVibrationSignal;
import de.feelspace.fslib.NavigationController;

public class ReactionTimeActivity extends AppCompatActivity {
    private Handler handler = new Handler();
    private Random random = new Random();
    private long vibrationStartTime;
    private boolean waitingForReaction = false;
    private int remainingVibrations = 30; // Anzahl der Vibrationen pro Testlauf
    private List<Long> reactionTimes = new ArrayList<>(); // Speicherung der Reaktionszeiten
    private Button startTestButton, backButton;

    private AppController appController;
    private NavigationController navController;
    private BeltCommandInterface beltCommand;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_reaction_time);

        appController = AppController.getInstance();
        navController = appController.getNavigationController();
        beltCommand = navController.getBeltConnection().getCommandInterface();

        startTestButton = findViewById(R.id.startTestButton);
        backButton = findViewById(R.id.backButton);

        startTestButton.setOnClickListener(v -> startTest());
        backButton.setOnClickListener(v -> finish());
    }

    private void startTest() {
        reactionTimes.clear();
        remainingVibrations = 30;
        backButton.setVisibility(View.GONE); // Zurück-Button ausblenden
        startTestButton.setEnabled(false);
        scheduleNextVibration();
    }

    private void scheduleNextVibration() {
        if (remainingVibrations <= 0) {
            finishTest();
            return;
        }

        long delay = random.nextInt(9990) + 15; // Zufällige Pause (10 ms – 15 s)

        handler.postDelayed(() -> {
            int position = random.nextInt(8); // Zufällige Gürtel-Position (0-7)
            Log.d("ReactionTimeTest", "Vibration an Position: " + position);

            // Gürtel in App-Modus setzen, falls nötig
            beltCommand.changeMode(BeltMode.APP);

            // Kurze Vibration auslösen
            beltCommand.vibrateAtPositions(new int[]{position}, 100, BeltVibrationSignal.APPROACHING_DESTINATION, 1, false);

            vibrationStartTime = SystemClock.elapsedRealtime();
            waitingForReaction = true;
        }, delay);
    }

    private void recordReactionTime() {
        if (waitingForReaction) {
            long reactionTime = SystemClock.elapsedRealtime() - vibrationStartTime;
            reactionTimes.add(reactionTime);
            Log.d("ReactionTimeTest", "Reaktion: " + reactionTime + " ms");

            waitingForReaction = false;
            remainingVibrations--;
            scheduleNextVibration();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        if (event.getAction() == MotionEvent.ACTION_DOWN) {
            recordReactionTime(); // Reaktion auf beliebige Berührung registrieren
            return true;
        }
        return super.onTouchEvent(event);
    }

    private void finishTest() {
        Log.d("ReactionTimeTest", "Alle Reaktionszeiten: " + reactionTimes.toString());

        if (!reactionTimes.isEmpty()) {
            long sum = 0;
            for (long time : reactionTimes) {
                sum += time;
            }
            long averageReactionTime = sum / reactionTimes.size();
            Log.d("ReactionTimeTest", "Durchschnittliche Reaktionszeit: " + averageReactionTime + " ms");
        } else {
            Log.d("ReactionTimeTest", "Keine gültigen Reaktionszeiten erfasst.");
        }

        backButton.setVisibility(View.VISIBLE);
        startTestButton.setEnabled(true);
    }
}
