package uk.joeuk89.radioalarm;

import android.os.Bundle;

import androidx.activity.OnBackPressedCallback;

import com.getcapacitor.BridgeActivity;

public class MainActivity extends BridgeActivity {

    @Override
    public void onCreate(Bundle savedInstanceState) {
        registerPlugin(AlarmPlugin.class);
        super.onCreate(savedInstanceState);

        // The phone's back gesture: let the web UI go back a screen (or ask about unsaved
        // changes). Only when it has nowhere to go does the app drop to the background.
        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                if (getBridge() == null || getBridge().getWebView() == null) {
                    moveTaskToBack(true);
                    return;
                }
                getBridge().getWebView().evaluateJavascript(
                    "(window.handleBack ? window.handleBack() : false)",
                    handled -> {
                        if (!"true".equals(handled)) moveTaskToBack(true);
                    });
            }
        });
    }
}
