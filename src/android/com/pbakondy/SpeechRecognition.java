// https://developer.android.com/reference/android/speech/SpeechRecognizer.html

package com.pbakondy;

import org.apache.cordova.CallbackContext;
import org.apache.cordova.CordovaInterface;
import org.apache.cordova.CordovaPlugin;
import org.apache.cordova.CordovaWebView;
import org.apache.cordova.PluginResult;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ContentResolver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.pm.PackageManager;

import android.Manifest;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.media.AudioManager;
import android.media.MediaPlayer;
import android.media.MediaRecorder;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;

import android.os.CountDownTimer;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.speech.RecognitionListener;
import android.speech.RecognizerIntent;
import android.speech.SpeechRecognizer;

import android.util.Log;
import android.view.Gravity;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.RequiresApi;

import com.arthenica.mobileffmpeg.Config;
import com.arthenica.mobileffmpeg.FFmpeg;
import com.langkingdom.langkingdom.R;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static android.widget.Toast.LENGTH_LONG;
import static com.arthenica.mobileffmpeg.FFmpeg.RETURN_CODE_CANCEL;
import static com.arthenica.mobileffmpeg.FFmpeg.RETURN_CODE_SUCCESS;

public class SpeechRecognition extends CordovaPlugin {

  private static final String LOG_TAG = "SpeechRecognition";

  private static final int REQUEST_CODE_PERMISSION = 2001;
  private static final int REQUEST_CODE_SPEECH = 2002;
  private static final String IS_RECOGNITION_AVAILABLE = "isRecognitionAvailable";
  private static final String START_LISTENING = "startListening";
  private static final String STOP_LISTENING = "stopListening";
  private static final String GET_SUPPORTED_LANGUAGES = "getSupportedLanguages";
  private static final String HAS_PERMISSION = "hasPermission";
  private static final String REQUEST_PERMISSION = "requestPermission";
  private static final int MAX_RESULTS = 5;
  private static final String NOT_AVAILABLE = "Speech recognition service is not available on the system.";
  private static final String MISSING_PERMISSION = "Missing permission";

  private JSONArray mLastPartialResults = new JSONArray();

  private static final String RECORD_AUDIO_PERMISSION = Manifest.permission.RECORD_AUDIO;

  private CallbackContext callbackContext;
  private LanguageDetailsChecker languageDetailsChecker;
  private Activity activity;
  private Context context;
  private View view;
  private SpeechRecognizer recognizer;
  private File mUserVoiceFile;
  private CountDownTimer mToastTimerCountDown;

  @Override
  public void initialize(CordovaInterface cordova, CordovaWebView webView) {
    super.initialize(cordova, webView);

    activity = cordova.getActivity();
    context = webView.getContext();
    view = webView.getView();

    view.post(new Runnable() {
      @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
      @Override
      public void run() {
        recognizer = SpeechRecognizer.createSpeechRecognizer(activity);

        // Init speech reg 1st time.
        if (audioPermissionGranted(RECORD_AUDIO_PERMISSION)) {
          final AudioManager audioManager = (AudioManager) activity.getSystemService(Context.AUDIO_SERVICE);
          try {
            /*
            try {
              if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.GINGERBREAD && Settings.Global.getInt(activity.getContentResolver(), "zen_mode") == 0) {
                audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_MUTE, 0);
              }
            } catch (Settings.SettingNotFoundException ex) {
              // Ignore;
            }
            */
            final Intent intent = new Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS);
            recognizer.startListening(intent);
            recognizer.cancel();
          } catch (SecurityException ex) {
            activity.runOnUiThread(new Runnable() {
              @Override
              public void run() {
                AlertDialog.Builder alertDialogBuilder = new AlertDialog.Builder(activity);
                alertDialogBuilder.setTitle("Speech Recognition");
                alertDialogBuilder.setMessage("Please install Google App from Google Play to use feature Speech Recognition.");
                alertDialogBuilder.setNegativeButton("Dismiss", new DialogInterface.OnClickListener() {
                  public void onClick(DialogInterface dialog, int which) {
                    try {
                      activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.googlequicksearchbox")));
                    } catch (android.content.ActivityNotFoundException anfe) {
                      activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.googlequicksearchbox")));
                    }
                    dialog.dismiss();
                  }
                });

                AlertDialog dialog = alertDialogBuilder.create();
                // Showing Alert Message
                alertDialogBuilder.show();
              }
            });
          } finally {
            /*
            final Handler handler = new Handler(Looper.getMainLooper());
            handler.postDelayed(new Runnable() {
              @RequiresApi(api = Build.VERSION_CODES.JELLY_BEAN_MR1)
              @Override
              public void run() {
                try {
                  if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.GINGERBREAD && Settings.Global.getInt(activity.getContentResolver(), "zen_mode") == 0) {
                    audioManager.adjustStreamVolume(AudioManager.STREAM_NOTIFICATION, AudioManager.ADJUST_UNMUTE, 0);
                  }
                } catch (Settings.SettingNotFoundException ex) {
                  // Ignore;
                }
              }
            }, 1000);*/
          }
        }

        SpeechRecognitionListener listener = new SpeechRecognitionListener();
        recognizer.setRecognitionListener(listener);
      }
    });
  }

  @Override
  public boolean execute(String action, JSONArray args, CallbackContext callbackContext) throws JSONException {
    this.callbackContext = callbackContext;

    Log.d(LOG_TAG, "execute() action " + action);

    try {
      if (IS_RECOGNITION_AVAILABLE.equals(action)) {
        boolean available = isRecognitionAvailable();
        PluginResult result = new PluginResult(PluginResult.Status.OK, available);
        callbackContext.sendPluginResult(result);
        return true;
      }

      if (START_LISTENING.equals(action)) {
        if (!isRecognitionAvailable()) {
          callbackContext.error(NOT_AVAILABLE);
          return true;
        }
        if (!audioPermissionGranted(RECORD_AUDIO_PERMISSION)) {
          callbackContext.error(MISSING_PERMISSION);
          return true;
        }

        String lang = args.optString(0);
        if (lang == null || lang.isEmpty() || lang.equals("null")) {
          lang = Locale.getDefault().toString();
        }

        int matches = args.optInt(1, MAX_RESULTS);

        String prompt = args.optString(2);
        if (prompt == null || prompt.isEmpty() || prompt.equals("null")) {
          prompt = null;
        }

        mLastPartialResults = new JSONArray();
        Boolean showPartial = args.optBoolean(3, false);
        Boolean showPopup = args.optBoolean(4, true);
        startListening(lang, matches, prompt,showPartial, showPopup);

        return true;
      }

      if (STOP_LISTENING.equals(action)) {
        final CallbackContext callbackContextStop = this.callbackContext;
        view.post(new Runnable() {
          @Override
          public void run() {
            if(recognizer != null) {
              recognizer.stopListening();
            }
            callbackContextStop.success(mUserVoiceFile != null? "file://" + mUserVoiceFile.getAbsolutePath(): "");
          }
        });
        return true;
      }

      if (GET_SUPPORTED_LANGUAGES.equals(action)) {
        getSupportedLanguages();
        return true;
      }

      if (HAS_PERMISSION.equals(action)) {
        hasAudioPermission();
        return true;
      }

      if (REQUEST_PERMISSION.equals(action)) {
        requestAudioPermission();
        return true;
      }

    } catch (Exception e) {
      e.printStackTrace();
      callbackContext.error(e.getMessage());
    }

    return false;
  }

  private boolean isRecognitionAvailable() {
    return SpeechRecognizer.isRecognitionAvailable(context);
  }

  private void startListening(String language, int matches, String prompt, final Boolean showPartial, Boolean showPopup) {
    Log.d(LOG_TAG, "startListening() language: " + language + ", matches: " + matches + ", prompt: " + prompt + ", showPartial: " + showPartial + ", showPopup: " + showPopup);
    mUserVoiceFile = null;

    final Intent intent = new Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
            RecognizerIntent.LANGUAGE_MODEL_FREE_FORM);
    intent.putExtra(RecognizerIntent.EXTRA_LANGUAGE, language);
    intent.putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, matches);
    intent.putExtra(RecognizerIntent.EXTRA_CALLING_PACKAGE,
            activity.getPackageName());
    intent.putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, showPartial);
    intent.putExtra("android.speech.extra.DICTATION_MODE", showPartial);
    intent.putExtra("android.speech.extra.GET_AUDIO_FORMAT", "audio/AMR");
    intent.putExtra("android.speech.extra.GET_AUDIO", true);
    if (prompt != null) {
      intent.putExtra(RecognizerIntent.EXTRA_PROMPT, prompt);
    }

    if (showPopup) {
      cordova.startActivityForResult(this, intent, REQUEST_CODE_SPEECH);

      if (prompt != null && prompt.trim().length() > 0) {
        mToastTimerCountDown = showToast(prompt);
      }
    } else {
      //final String fileName = context.getExternalCacheDir().getAbsolutePath() + "/audiorecordtest.3gp";
      //s_filename = fileName;
      view.post(new Runnable() {
        @Override
        public void run() {
          recognizer.startListening(intent);
          //startRecording(fileName);
        }
      });
    }
  }

  private static String s_filename = "";

  private static void startPlaying(String fileName) {
    MediaPlayer player = new MediaPlayer();
    try {
      player.setDataSource(fileName);
      player.prepare();
      player.start();
    } catch (Exception e) {
      Log.e(LOG_TAG, "prepare() failed");
    }
  }

  private static void stopRecording() {
    recorder.stop();
    recorder.release();
    recorder = null;

    startPlaying(s_filename);
  }

  private static void startRecording(String fileName) {
    recorder = new MediaRecorder();
    recorder.setAudioSource(MediaRecorder.AudioSource.MIC);
    recorder.setOutputFormat(MediaRecorder.OutputFormat.THREE_GPP);
    recorder.setOutputFile(fileName);
    recorder.setAudioEncoder(MediaRecorder.AudioEncoder.AAC);
    try {
      recorder.prepare();
    } catch (Exception e) {
      Log.e(LOG_TAG, "prepare() failed");
    }

    recorder.start();
  }

  private static MediaRecorder recorder;

  private Toast mToastToShow;
  public CountDownTimer showToast(String msg) {
    LayoutInflater inflater =  cordova.getActivity().getLayoutInflater();
    View layout = inflater.inflate(R.layout.speech_reg_prompt, (ViewGroup) cordova.getActivity().findViewById(R.id.speech_reg_prompt_layout));
    TextView tv = (TextView) layout.findViewById(R.id.txtPrompt);
    tv.setText(msg);

    mToastToShow = new Toast(context);
    mToastToShow.setGravity(Gravity.CENTER_VERTICAL| Gravity.CENTER_HORIZONTAL, 0, 600);
    mToastToShow.setDuration(Toast.LENGTH_SHORT);
    mToastToShow.setView(layout);

    // Set the countdown to display the toast
    CountDownTimer toastCountDown;
    toastCountDown = new CountDownTimer(200000, 1000 /*Tick duration*/) {
      public void onTick(long millisUntilFinished) {
        activity.runOnUiThread(new Runnable() {
          @Override
          public void run() {
            Log.d(LOG_TAG, "###Toast repeated");
            try {
              if (mToastTimerCountDown != null) {
                mToastToShow.show();
              }
            } catch (Throwable ex) {
              // Ignore
            }
          }
        });
      }
      public void onFinish() {
        mToastToShow.cancel();
      }
    };
    mToastToShow.show();
    toastCountDown.start();
    return toastCountDown;
  }

  private void getSupportedLanguages() {
    if (languageDetailsChecker == null) {
      languageDetailsChecker = new LanguageDetailsChecker(callbackContext);
    }

    List<String> supportedLanguages = languageDetailsChecker.getSupportedLanguages();
    if (supportedLanguages != null) {
      JSONArray languages = new JSONArray(supportedLanguages);
      callbackContext.success(languages);
      return;
    }

    Intent detailsIntent = new Intent(RecognizerIntent.ACTION_GET_LANGUAGE_DETAILS);
    activity.sendOrderedBroadcast(detailsIntent, null, languageDetailsChecker, null, Activity.RESULT_OK, null, null);
  }

  private void hasAudioPermission() {
    PluginResult result = new PluginResult(PluginResult.Status.OK, audioPermissionGranted(RECORD_AUDIO_PERMISSION));
    this.callbackContext.sendPluginResult(result);
  }

  private void requestAudioPermission() {
    requestPermission(RECORD_AUDIO_PERMISSION);
  }

  private boolean audioPermissionGranted(String type) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
      return true;
    }
    return cordova.hasPermission(type);
  }

  private void requestPermission(String type) {
    if (!audioPermissionGranted(type)) {
      cordova.requestPermission(this, 23456, type);
    } else {
      this.callbackContext.success();
    }
  }

  @Override
  public void onRequestPermissionResult(int requestCode, String[] permissions, int[] grantResults) throws JSONException {
    if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
      this.callbackContext.success();
    } else {
      this.callbackContext.error("Permission denied");
    }
  }

  private String covertAmrToMp4(String inputFile) {
    String outputFile = inputFile.replaceAll(".amr", ".m4a");

    int rc = FFmpeg.execute("-ss 0.2 -i " + inputFile + " -ar 22050 " + outputFile);

    if (rc == RETURN_CODE_SUCCESS) {
      Log.i(Config.TAG, "Command execution completed successfully.");
      (new File(inputFile)).delete();
    } else if (rc == RETURN_CODE_CANCEL) {
      Log.i(Config.TAG, "Command execution cancelled by user.");
    } else {
      Log.i(Config.TAG, String.format("Command execution failed with rc=%d and the output below.", rc));
    }

    return outputFile;
  }

  @Override
  public void onActivityResult(int requestCode, int resultCode, Intent data) {
    Log.d(LOG_TAG, "onActivityResult() requestCode: " + requestCode + ", resultCode: " + resultCode);
    if (requestCode == REQUEST_CODE_SPEECH) {
      if (mToastTimerCountDown != null) {
        mToastTimerCountDown.cancel();
        mToastTimerCountDown = null;
      }
      if (mToastToShow != null) {
        mToastToShow.cancel();
      }
      if (resultCode == Activity.RESULT_OK) {
        try {
          Uri audioUri = data.getData();
          ContentResolver contentResolver = this.context.getContentResolver();
          InputStream filestream = contentResolver.openInputStream(audioUri);
          byte[] buffer = new byte[filestream.available()];
          filestream.read(buffer);
          mUserVoiceFile = File.createTempFile("voice", ".amr", context.getCacheDir());
          OutputStream outStream = new FileOutputStream(mUserVoiceFile);
          outStream.write(buffer);
          mUserVoiceFile = new File(covertAmrToMp4(mUserVoiceFile.getAbsolutePath()));

          ArrayList<String> matches = data.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS);
          Map resultMap = new HashMap();
          resultMap.put("isFinal", true);
          resultMap.put("matches", matches);
          this.callbackContext.success(new JSONObject(resultMap));
        } catch (Exception e) {
          e.printStackTrace();
          this.callbackContext.error(e.getMessage());
        }
      } else {
        this.callbackContext.error(Integer.toString(resultCode));
      }
      return;
    }

    super.onActivityResult(requestCode, resultCode, data);
  }


  private class SpeechRecognitionListener implements RecognitionListener {

    @Override
    public void onBeginningOfSpeech() {
    }

    @Override
    public void onBufferReceived(byte[] buffer) {
    }

    @Override
    public void onEndOfSpeech() {
    }

    @Override
    public void onError(int errorCode) {
      String errorMessage = getErrorText(errorCode);
      Log.d(LOG_TAG, "Error: " + errorMessage);
      callbackContext.error(errorMessage);
    }

    @Override
    public void onEvent(int eventType, Bundle params) {
    }

    @Override
    public void onPartialResults(Bundle bundle) {
      ArrayList<String> matches = bundle.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
      Log.d(LOG_TAG, "SpeechRecognitionListener partialResults: " + matches);
      JSONArray matchesJSON = new JSONArray(matches);
      try {
        if (matches != null
                && matches.size() > 0
                        && !mLastPartialResults.equals(matchesJSON)) {
          mLastPartialResults = matchesJSON;

          Map resultMap = new HashMap();
          resultMap.put("isPartial", true);
          resultMap.put("matches", matches);
          PluginResult pluginResult = new PluginResult(PluginResult.Status.OK, new JSONObject(resultMap));
          pluginResult.setKeepCallback(true);
          callbackContext.sendPluginResult(pluginResult);
        }
      } catch (Exception e) {
        e.printStackTrace();
        callbackContext.error(e.getMessage());
      }
    }

    @Override
    public void onReadyForSpeech(Bundle params) {
      Log.d(LOG_TAG, "onReadyForSpeech");
    }

    @Override
    public void onResults(Bundle results) {
      ArrayList<String> matches = results.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION);
      Log.d(LOG_TAG, "SpeechRecognitionListener results: " + matches);
      try {
        Map resultMap = new HashMap();
        resultMap.put("isFinal", true);
        resultMap.put("matches", matches);
        callbackContext.success(new JSONObject(resultMap));
        //SpeechRecognition.stopRecording();
      } catch (Exception e) {
        e.printStackTrace();
        callbackContext.error(e.getMessage());
      }
    }

    @Override
    public void onRmsChanged(float rmsdB) {
    }

    private String getErrorText(int errorCode) {
      String message;
      switch (errorCode) {
        case SpeechRecognizer.ERROR_AUDIO:
          message = "Audio recording error";
          break;
        case SpeechRecognizer.ERROR_CLIENT:
          message = "Client side error";
          break;
        case SpeechRecognizer.ERROR_INSUFFICIENT_PERMISSIONS:
          message = "Insufficient permissions";
          break;
        case SpeechRecognizer.ERROR_NETWORK:
          message = "Network error";
          break;
        case SpeechRecognizer.ERROR_NETWORK_TIMEOUT:
          message = "Network timeout";
          break;
        case SpeechRecognizer.ERROR_NO_MATCH:
          message = "No match";
          break;
        case SpeechRecognizer.ERROR_RECOGNIZER_BUSY:
          message = "RecognitionService busy";
          break;
        case SpeechRecognizer.ERROR_SERVER:
          message = "error from server";
          break;
        case SpeechRecognizer.ERROR_SPEECH_TIMEOUT:
          message = "No speech input";
          break;
        default:
          message = "Didn't understand, please try again.";
          break;
      }
      return message;
    }
  }

}
