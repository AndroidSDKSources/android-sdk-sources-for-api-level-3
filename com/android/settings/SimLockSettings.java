/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.android.settings;

import android.content.Context;
import android.content.res.Resources;
import android.os.AsyncResult;
import android.os.Bundle;
import android.os.Handler;
import android.os.Message;
import android.preference.Preference;
import android.preference.PreferenceActivity;
import android.preference.CheckBoxPreference;
import android.preference.PreferenceScreen;
import com.android.internal.telephony.Phone;
import com.android.internal.telephony.PhoneFactory;
import android.widget.Toast;

/**
 * Implements the preference screen to enable/disable SIM lock and
 * also the dialogs to change the SIM PIN. In the former case, enabling/disabling
 * the SIM lock will prompt the user for the current PIN.
 * In the Change PIN case, it prompts the user for old pin, new pin and new pin
 * again before attempting to change it. Calls the SimCard interface to execute
 * these operations.
 *
 */
public class SimLockSettings extends PreferenceActivity 
        implements EditPinPreference.OnPinEnteredListener {

    private static final int OFF_MODE = 0;
    // State when enabling/disabling SIM lock
    private static final int SIM_LOCK_MODE = 1;
    // State when entering the old pin
    private static final int SIM_OLD_MODE = 2;
    // State when entering the new pin - first time
    private static final int SIM_NEW_MODE = 3;
    // State when entering the new pin - second time
    private static final int SIM_REENTER_MODE = 4;
    
    // Keys in xml file
    private static final String PIN_DIALOG = "sim_pin";
    private static final String PIN_TOGGLE = "sim_toggle";
    // Keys in icicle
    private static final String DIALOG_STATE = "dialogState";
    private static final String DIALOG_PIN = "dialogPin";
    private static final String DIALOG_ERROR = "dialogError";
    private static final String ENABLE_TO_STATE = "enableState";
    
    private static final int MIN_PIN_LENGTH = 4;
    private static final int MAX_PIN_LENGTH = 8;
    // Which dialog to show next when popped up
    private int mDialogState = OFF_MODE;
    
    private String mPin;
    private String mOldPin;
    private String mNewPin;
    private String mError;
    // Are we trying to enable or disable SIM lock?
    private boolean mToState;
    
    private Phone mPhone;
    
    private EditPinPreference mPinDialog;
    private CheckBoxPreference mPinToggle;
    
    private Resources mRes;

    // For async handler to identify request type
    private static final int ENABLE_SIM_PIN_COMPLETE = 100;
    private static final int CHANGE_SIM_PIN_COMPLETE = 101;

    // For replies from SimCard interface
    private Handler mHandler = new Handler() {
        public void handleMessage(Message msg) {
            AsyncResult ar = (AsyncResult) msg.obj;
            switch (msg.what) {
                case ENABLE_SIM_PIN_COMPLETE:
                    simLockChanged(ar.exception == null);
                    break;
                case CHANGE_SIM_PIN_COMPLETE:
                    simPinChanged(ar.exception == null);
                    break;
            }

            return;
        }
    };
    
    // For top-level settings screen to query
    static boolean isSimLockEnabled() {
        return PhoneFactory.getDefaultPhone().getSimCard().getSimLockEnabled();
    }
    
    static String getSummary(Context context) {
        Resources res = context.getResources();
        String summary = isSimLockEnabled() 
                ? res.getString(R.string.sim_lock_on) 
                : res.getString(R.string.sim_lock_off);
        return summary;
    }
    
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
    
        addPreferencesFromResource(R.xml.sim_lock_settings);
        
        mPinDialog = (EditPinPreference) findPreference(PIN_DIALOG);
        mPinToggle = (CheckBoxPreference) findPreference(PIN_TOGGLE);
        if (savedInstanceState != null && savedInstanceState.containsKey(DIALOG_STATE)) {
            mDialogState = savedInstanceState.getInt(DIALOG_STATE);
            mPin = savedInstanceState.getString(DIALOG_PIN);
            mError = savedInstanceState.getString(DIALOG_ERROR);
            mToState = savedInstanceState.getBoolean(ENABLE_TO_STATE);
        }

        mPinDialog.setOnPinEnteredListener(this);
        
        // Don't need any changes to be remembered
        getPreferenceScreen().setPersistent(false);
        
        mPhone = PhoneFactory.getDefaultPhone();
        mRes = getResources();
    }
    
    @Override
    protected void onResume() {
        super.onResume();
        
        mPinToggle.setChecked(mPhone.getSimCard().getSimLockEnabled());
        
        if (mDialogState != OFF_MODE) {
            showPinDialog();
        } else {
            // Prep for standard click on "Change PIN"
            resetDialogState();
        }
    }
    
    @Override
    protected void onSaveInstanceState(Bundle out) {
        // Need to store this state for slider open/close
        // There is one case where the dialog is popped up by the preference
        // framework. In that case, let the preference framework store the
        // dialog state. In other cases, where this activity manually launches
        // the dialog, store the state of the dialog.
        if (mPinDialog.isDialogOpen()) {
            out.putInt(DIALOG_STATE, mDialogState);
            out.putString(DIALOG_PIN, mPinDialog.getEditText().getText().toString());
            out.putString(DIALOG_ERROR, mError);
            out.putBoolean(ENABLE_TO_STATE, mToState);
        } else {
            super.onSaveInstanceState(out);
        }
    }

    private void showPinDialog() {
        if (mDialogState == OFF_MODE) {
            return;
        }
        setDialogValues();
        
        mPinDialog.showPinDialog();
    }
    
    private void setDialogValues() {
        mPinDialog.setText(mPin);
        String message = "";
        switch (mDialogState) {
            case SIM_LOCK_MODE:
                message = mRes.getString(R.string.sim_enter_pin);
                mPinDialog.setDialogTitle(mToState 
                        ? mRes.getString(R.string.sim_enable_sim_lock)
                        : mRes.getString(R.string.sim_disable_sim_lock));
                break;
            case SIM_OLD_MODE:
                message = mRes.getString(R.string.sim_enter_old);
                mPinDialog.setDialogTitle(mRes.getString(R.string.sim_change_pin));
                break;
            case SIM_NEW_MODE:
                message = mRes.getString(R.string.sim_enter_new);
                mPinDialog.setDialogTitle(mRes.getString(R.string.sim_change_pin));
                break;
            case SIM_REENTER_MODE:
                message = mRes.getString(R.string.sim_reenter_new);
                mPinDialog.setDialogTitle(mRes.getString(R.string.sim_change_pin));
                break;
        }
        if (mError != null) {
            message = mError + "\n" + message;
            mError = null;
        }
        mPinDialog.setDialogMessage(message);
    }

    public void onPinEntered(EditPinPreference preference, boolean positiveResult) {
        if (!positiveResult) {
            resetDialogState();
            return;
        }
        
        mPin = preference.getText();
        if (!reasonablePin(mPin)) {
            // inject error message and display dialog again
            mError = mRes.getString(R.string.sim_bad_pin);
            showPinDialog();
            return;
        }
        switch (mDialogState) {
            case SIM_LOCK_MODE:
                tryChangeSimLockState();
                break;
            case SIM_OLD_MODE:
                mOldPin = mPin;
                mDialogState = SIM_NEW_MODE;
                mError = null;
                mPin = null;
                showPinDialog();
                break;
            case SIM_NEW_MODE:
                mNewPin = mPin;
                mDialogState = SIM_REENTER_MODE;
                mPin = null;
                showPinDialog();
                break;
            case SIM_REENTER_MODE:
                if (!mPin.equals(mNewPin)) {
                    mError = mRes.getString(R.string.sim_pins_dont_match);
                    mDialogState = SIM_NEW_MODE;
                    mPin = null;
                    showPinDialog();
                } else {
                    mError = null;
                    tryChangePin();
                }
                break;
        }
    }
    
    public boolean onPreferenceTreeClick(PreferenceScreen preferenceScreen, Preference preference) {
        if (preference == mPinToggle) {
            // Get the new, preferred state
            mToState = mPinToggle.isChecked();
            // Flip it back and pop up pin dialog  
            mPinToggle.setChecked(!mToState);  
            mDialogState = SIM_LOCK_MODE;
            showPinDialog();
        }
        return true;
    }
    
    private void tryChangeSimLockState() {
        // Try to change sim lock. If it succeeds, toggle the lock state and 
        // reset dialog state. Else inject error message and show dialog again.
        Message callback = Message.obtain(mHandler, ENABLE_SIM_PIN_COMPLETE);
        mPhone.getSimCard().setSimLockEnabled(mToState, mPin, callback);

    }
    
    private void simLockChanged(boolean success) {
        if (success) {
            mPinToggle.setChecked(mToState);
        } else {
            // TODO: I18N
            Toast.makeText(this, mRes.getString(R.string.sim_lock_failed), Toast.LENGTH_SHORT)
                    .show();
        }
        resetDialogState();
    }

    private void simPinChanged(boolean success) {
        if (!success) {
         // TODO: I18N
            Toast.makeText(this, mRes.getString(R.string.sim_change_failed), 
                    Toast.LENGTH_SHORT)
                    .show();
        } else {
            Toast.makeText(this, mRes.getString(R.string.sim_change_succeeded), 
                    Toast.LENGTH_SHORT)
                    .show();

        }
        resetDialogState();
    }

    private void tryChangePin() {
        Message callback = Message.obtain(mHandler, CHANGE_SIM_PIN_COMPLETE);
        mPhone.getSimCard().changeSimLockPassword(mOldPin,
                mNewPin, callback);
    }
    
    private boolean reasonablePin(String pin) {
        if (pin == null || pin.length() < MIN_PIN_LENGTH || pin.length() > MAX_PIN_LENGTH) {
            return false;
        } else {
            return true;
        }
    }
 
    private void resetDialogState() {
        mError = null;
        mDialogState = SIM_OLD_MODE; // Default for when Change PIN is clicked
        mPin = "";
        setDialogValues();
    }
}
