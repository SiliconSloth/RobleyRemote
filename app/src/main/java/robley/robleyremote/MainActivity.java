// Author: SiliconSloth 18/1/2018
package robley.robleyremote;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.MotionEvent;
import android.view.View;
import android.widget.ImageButton;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.VerticalSeekBar;

import java.io.IOException;
import java.util.Set;
import java.util.UUID;

/*
 * This app is used to remote control a robot running RobleyVision during training.
 * Remember to set LAPTOP_NAME before use.
 */

/*
 * Power serialization format:
 *
 * Motor powers can range between -100 and 100.  If a special value of 101 is sent for either motor
 * Trainer.py will stop the robot and save the training video to a file.
 *
 * In order to send motor powers over Bluetooth, they must first be converted into bytes.
 * One byte can represent any integer between 0 and 255, so 100 must be added to the powers
 * before they are sent so that they are in the range 0 to 201.
 */


public class MainActivity extends AppCompatActivity {
    //The Bluetooth name of the laptop to connect to.
    private final String LAPTOP_NAME = "MY-LAPTOP";

    //Same as the one in Trainer.py.
    private final UUID BT_UUID = UUID.fromString("dfdde353-e79e-49f0-bff0-43d12ca0fbec");
    private BluetoothSocket bluetoothSocket = null;

    private ImageButton stopButton;
    private VerticalSeekBar leftBar;
    private VerticalSeekBar rightBar;
    private TextView leftIndicator;
    private TextView rightIndicator;
    private TextView statusIndicator;

    private boolean controlsEnabled = false;
    private String statusMessage = "Connecting...";
    private final UpdateRunnable updateRunnable = new UpdateRunnable();

    private int powerLeft = 0;
    private int powerRight = 0;

    //Runs when the app starts.
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        stopButton = (ImageButton) findViewById(R.id.stop_button);
        leftBar = (VerticalSeekBar) findViewById(R.id.left_bar);
        rightBar = (VerticalSeekBar) findViewById(R.id.right_bar);
        leftIndicator = (TextView) findViewById(R.id.left_indicator);
        rightIndicator = (TextView) findViewById(R.id.right_indicator);
        statusIndicator = (TextView) findViewById(R.id.status_indicator);

        //Run the Bluetooth code on a background thread so as not to freeze the GUI
        //when waiting to connect.
        new Thread(new BluetoothRunnable()).start();

        //Make button wave effect originate at finger.
        stopButton.setOnTouchListener(new View.OnTouchListener() {
            @Override
            public boolean onTouch(View view, MotionEvent event) {
                ImageButton button = (ImageButton) view;
                ((ImageButton) view).getDrawable().setHotspot(event.getX() * button.getDrawable().getIntrinsicWidth() / button.getWidth(),
                        event.getY() * button.getDrawable().getIntrinsicHeight() / button.getHeight());
                return false;
            }
        });
        stopButton.setOnClickListener(new View.OnClickListener() {
            @Override
            public void onClick(View view) {
                //Set powers to a stop command.
                powerLeft = 101;
                powerRight = 101;

                //Set the sliders to the 0 position (50%).
                leftBar.setProgress(50);
                rightBar.setProgress(50);

                leftIndicator.setText("0");
                rightIndicator.setText("0");

                //Disable the controls and update the GUI.
                controlsEnabled = false;
                statusMessage = "Stopping...";
                updateRunnable.run();
            }
        });

        leftBar.setOnSeekBarChangeListener(new PowerBarListener());
        rightBar.setOnSeekBarChangeListener(new PowerBarListener());
    }

    private class PowerBarListener implements SeekBar.OnSeekBarChangeListener {
        @Override
        public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
            boolean isLeftBar = seekBar.getId() == R.id.left_bar;
            //Convert slider progress 0 to 100 to power -100 to 100.
            int power = progress*2 - 100;

            //Update the appropriate indicator label to show the new power.
            TextView indicator = isLeftBar? leftIndicator : rightIndicator;
            indicator.setText(String.valueOf(power));

            //Only update the power if a stop command has not been issued.
            if (powerLeft != 101 && powerRight != 101)
                if (isLeftBar) powerLeft = power;
                else powerRight = power;
        }

        @Override
        public void onStartTrackingTouch(SeekBar seekBar) {
        }
        @Override
        public void onStopTrackingTouch(SeekBar seekBar) {
        }
    }

    //Called to update the GUI.
    private class UpdateRunnable implements Runnable {
        @Override
        public void run() {
            //Enable or disable all the controls as needed and update the status message.
            if (controlsEnabled) {
                stopButton.setEnabled(true);
                stopButton.setImageResource(R.drawable.stop_ripple);
                leftBar.setEnabled(true);
                rightBar.setEnabled(true);
                statusIndicator.setVisibility(View.INVISIBLE);
            } else {
                stopButton.setEnabled(false);
                stopButton.setImageResource(R.drawable.stop_disabled);
                leftBar.setEnabled(false);
                rightBar.setEnabled(false);
                statusIndicator.setVisibility(View.VISIBLE);
            }
            statusIndicator.setText(statusMessage);
        }
    }

    //Loop that attempts to connect to the laptop over Bluetooth and then sends motor powers to it.
    //Runs on a separate thread to the GUI to avoid freezing.
    private class BluetoothRunnable implements Runnable {
        @Override
        public void run() {
            while (true) {
                if (bluetoothSocket == null) {  //If a connection hasn't been made yet...
                    //Disable the controls until Bluetooth is ready.
                    controlsEnabled = false;
                    runOnUiThread(updateRunnable);

                    //Access the Bluetooth adapter and make sure this phone actually supports Bluetooth.
                    BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
                    if (adapter == null) {
                        statusMessage = "Bluetooth not supported.";
                        runOnUiThread(updateRunnable);
                        continue;   //Try again until it works.
                    }
                    /* If the status message left from the last attempt is "Bluetooth not supported."
                     * but we just found that it is now supported, this message is no longer true so should
                     * be replaced with the generic "Connecting..." message.  It is probably not
                     * possible for the phone to suddenly gain Bluetooth support, but we do this
                     * just in case.
                     */
                    if (statusMessage.equals("Bluetooth not supported.")) {
                        statusMessage = "Connecting...";
                        runOnUiThread(updateRunnable);
                    }

                    //If the user has disabled Bluetooth, say so.
                    if (!adapter.isEnabled()) {
                        statusMessage = "Bluetooth not enabled.";
                        runOnUiThread(updateRunnable);
                        continue;
                    }
                    //Remove the message once they have enabled Bluetooth.
                    if (statusMessage.equals("Bluetooth not enabled.")) {
                        statusMessage = "Connecting...";
                        runOnUiThread(updateRunnable);
                    }

                    //Look for the laptop in the phone's paired devices.
                    Set<BluetoothDevice> pairedDevices = adapter.getBondedDevices();
                    BluetoothDevice laptop = null;
                    for (BluetoothDevice device : pairedDevices) {
                        if (device.getName().equals(LAPTOP_NAME)) {
                            laptop = device;
                            break;
                        }
                    }
                    if (laptop == null) {
                        statusMessage = "Laptop not paired.";
                        runOnUiThread(updateRunnable);
                        continue;
                    }
                    //Remove the message once the laptop is paired.
                    if (statusMessage.equals("Laptop not paired.")) {
                        statusMessage = "Connecting...";
                        runOnUiThread(updateRunnable);
                    }

                    try {
                        //Actually make the connection.
                        bluetoothSocket = laptop.createRfcommSocketToServiceRecord(BT_UUID);
                        bluetoothSocket.connect();
                    } catch (Throwable e) {
                        bluetoothSocket = null; //Show that there is still no connection.
                        statusMessage = "Can't connect.";
                        runOnUiThread(updateRunnable);
                        e.printStackTrace();
                        continue;
                    }

                    //If this is the first reconnection after the stop button was pressed,
                    //unset the stop command so we don't immediately stop the robot again.
                    if (powerLeft == 101 && powerRight == 101) {
                        powerLeft = 0;
                        powerRight = 0;
                    }

                    //Enable the controls once Bluetooth is ready.
                    controlsEnabled = true;
                    runOnUiThread(updateRunnable);
                } else {
                    //If Bluetooth is connected just send the current powers in a constant loop.
                    sendPowers(powerLeft, powerRight);
                }
            }
        }
    }

    //Responsible for actually sending commands to the laptop.
    private void sendPowers(int power1, int power2) {
        try {
            //Don't send if the connection isn't working.
            if (bluetoothSocket != null && bluetoothSocket.isConnected())
                //Convert the powers to bytes as explained in Trainer.py.
                bluetoothSocket.getOutputStream().write(new byte[]{(byte) (power1 + 100), (byte) (power2 + 100)});
        } catch (IOException e) {
            //If sending fails for some reason, close and reopen the Bluetooth connection.
            bluetoothSocket = null;
            statusMessage = "Connecting...";
            e.printStackTrace();
        }
    }
}
