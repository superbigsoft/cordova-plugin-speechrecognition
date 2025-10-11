// https://developer.apple.com/library/prerelease/content/samplecode/SpeakToMe/Listings/SpeakToMe_ViewController_swift.html
// http://robusttechhouse.com/introduction-to-native-speech-recognition-for-ios/
// https://www.appcoda.com/siri-speech-framework/

#import "SpeechRecognition.h"

#import <Cordova/CDV.h>
#import <Speech/Speech.h>

#define DEFAULT_LANGUAGE @"en-US"
#define DEFAULT_MATCHES 5

#define MESSAGE_MISSING_PERMISSION @"Missing permission"
#define MESSAGE_ACCESS_DENIED @"User denied access to speech recognition"
#define MESSAGE_RESTRICTED @"Speech recognition restricted on this device"
#define MESSAGE_NOT_DETERMINED @"Speech recognition not determined on this device"
#define MESSAGE_ACCESS_DENIED_MICROPHONE @"User denied access to microphone"
#define MESSAGE_ONGOING @"Ongoing speech recognition"

@interface SpeechRecognition()

@property (strong, nonatomic) SFSpeechRecognizer *speechRecognizer;
@property (strong, nonatomic) AVAudioEngine *audioEngine;
@property (strong, nonatomic) SFSpeechAudioBufferRecognitionRequest *recognitionRequest;
@property (strong, nonatomic) SFSpeechRecognitionTask *recognitionTask;
@property (strong, nonatomic) AVAudioRecorder *avAudioRecorder;

@end


@implementation SpeechRecognition

- (void)pluginInitialize {
    self.audioEngine = [[AVAudioEngine alloc] init];
}

- (void)isRecognitionAvailable:(CDVInvokedUrlCommand*)command {
    CDVPluginResult *pluginResult = nil;

    if ([SFSpeechRecognizer class]) {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsBool:YES];
    } else {
        pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsBool:NO];
    }

    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)returnErrorForCommand:(CDVInvokedUrlCommand*)command andError:(NSError *)error {
    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:error.description];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)startListening:(CDVInvokedUrlCommand*)command {
    if ( self.audioEngine.isRunning ) {
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:MESSAGE_ONGOING];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }

    NSLog(@"startListening()");

    SFSpeechRecognizerAuthorizationStatus status = [SFSpeechRecognizer authorizationStatus];
    if (status != SFSpeechRecognizerAuthorizationStatusAuthorized) {
        NSLog(@"startListening() speech recognition access not authorized");
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:MESSAGE_MISSING_PERMISSION];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }

    [[AVAudioSession sharedInstance] requestRecordPermission:^(BOOL granted){
        if (!granted) {
            NSLog(@"startListening() microphone access not authorized");
            CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:MESSAGE_ACCESS_DENIED_MICROPHONE];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            return;
        }

        NSString* language = [command argumentAtIndex:0 withDefault:DEFAULT_LANGUAGE];
        int matches = [[command argumentAtIndex:1 withDefault:@(DEFAULT_MATCHES)] intValue];
        BOOL showPartial = [[command argumentAtIndex:3 withDefault:@(NO)] boolValue];


        // Cancel the previous task if it's running.
        if ( self.recognitionTask ) {
            [self.recognitionTask cancel];
            [self.recognitionTask finish];
            self.recognitionTask = nil;
        }

        [self.audioEngine reset];
        AVAudioSession *audioSession = [AVAudioSession sharedInstance];
        
        NSError *error = nil;
        
        [audioSession setCategory:AVAudioSessionCategoryPlayAndRecord withOptions:AVAudioSessionCategoryOptionDefaultToSpeaker error: &error];
        
        if (error) {
            [self returnErrorForCommand:command andError:error];
            return;
        }
        
        [audioSession setMode:AVAudioSessionModeDefault error:&error];
        if (error) {
            [self returnErrorForCommand:command andError:error];
            return;
        }
        
        [audioSession setActive:YES withOptions:AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation error:&error];
        
        if (error) {
            [self returnErrorForCommand:command andError:error];
            return;
        }
        
        AVAudioInputNode *inputNode = self.audioEngine.inputNode;
        AVAudioFormat *format = [inputNode outputFormatForBus:0];
        NSLog(@"format channelCount: %d, sampleRate: %f", format.channelCount, format.sampleRate);
        NSLog(@"inputNode channelCount: %d, sampleRate: %f", [inputNode outputFormatForBus:0].channelCount, [inputNode outputFormatForBus:0].sampleRate);

        NSLocale *locale = [[NSLocale alloc] initWithLocaleIdentifier:language];
        self.speechRecognizer = [[SFSpeechRecognizer alloc] initWithLocale:locale];
        self.recognitionRequest = [[SFSpeechAudioBufferRecognitionRequest alloc] init];
        self.recognitionRequest.shouldReportPartialResults = showPartial;
        self.recognitionTask = [self.speechRecognizer recognitionTaskWithRequest:self.recognitionRequest resultHandler:^(SFSpeechRecognitionResult *result, NSError *error) {

            if ( result ) {

                NSMutableArray *resultArray = [[NSMutableArray alloc] init];

                int counter = 0;
                for ( SFTranscription *transcription in result.transcriptions ) {
                    if (matches > 0 && counter < matches) {
                        [resultArray addObject:transcription.formattedString];
                    }
                    counter++;
                }

                NSArray *transcriptions = [NSArray arrayWithArray:resultArray];

                NSLog(@"startListening() recognitionTask result array: %@", transcriptions.description);

                CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsDictionary:@{@"isFinal": @(result.isFinal), @"isPartial": @(!result.isFinal), @"matches": transcriptions}];
                if (showPartial){
                    [pluginResult setKeepCallbackAsBool:YES];
                }
                [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            }

            if ( error ) {
                NSLog(@"startListening() recognitionTask error: %@", error.description);
                [self stopAll];

                CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:error.description];
                if (showPartial){
                    [pluginResult setKeepCallbackAsBool:YES];
                }
                [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            }

            if ( result.isFinal ) {
                NSLog(@"startListening() recognitionTask isFinal");
                [self stopAll];
            }
        }];
        


        [inputNode removeTapOnBus:0];
        @try {
            [inputNode installTapOnBus:0 bufferSize:1024 format:format block:^(AVAudioPCMBuffer *buffer, AVAudioTime *when) {
                [self.recognitionRequest appendAudioPCMBuffer:buffer];
            }];

            [self.audioEngine prepare];
            [self.audioEngine startAndReturnError:nil];
            self.avAudioRecorder = [self record];
        }
        @catch (NSException *error) {
            CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:error.description];
            [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            [self stopAll];
        }
    }];

}

- (void)stopAll {
    [self stopAudioRecording];
    
    [self.audioEngine stop];
    [self.audioEngine.inputNode removeTapOnBus:0];

    [self.recognitionRequest endAudio];
    self.recognitionRequest = nil;
    
    [self.recognitionTask cancel];
    [self.recognitionTask finish];
    self.recognitionTask = nil;
}

- (NSString *) dateString
{
    // return a formatted string for a file name
    NSDateFormatter *formatter = [[NSDateFormatter alloc] init];
    formatter.dateFormat = @"ddMMMYY_hhmmssa";
    return [[formatter stringFromDate:[NSDate date]] stringByAppendingString:@".m4a"];
}

- (AVAudioRecorder*) record
{
    NSError *error;

    // Recording settings
    NSMutableDictionary *settings = [NSMutableDictionary dictionary];

    [settings setValue: [NSNumber numberWithInt:kAudioFormatMPEG4AAC] forKey:AVFormatIDKey];
    [settings setValue: [NSNumber numberWithFloat:8000.0] forKey:AVSampleRateKey];
    [settings setValue: [NSNumber numberWithInt: 1] forKey:AVNumberOfChannelsKey];
    [settings setValue: [NSNumber numberWithInt:16] forKey:AVLinearPCMBitDepthKey];
    [settings setValue: [NSNumber numberWithBool:NO] forKey:AVLinearPCMIsBigEndianKey];
    [settings setValue: [NSNumber numberWithBool:NO] forKey:AVLinearPCMIsFloatKey];
    [settings setValue:  [NSNumber numberWithInt: AVAudioQualityMax] forKey:AVEncoderAudioQualityKey];
     NSArray *searchPaths =NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, YES);
    NSString *documentPath_ = [searchPaths objectAtIndex: 0];

    NSString *pathToSave = [documentPath_ stringByAppendingPathComponent:[self dateString]];

    // File URL
    NSURL *url = [NSURL fileURLWithPath:pathToSave];//FILEPATH];

    // Create recorder
    AVAudioRecorder* recorder = [[AVAudioRecorder alloc] initWithURL:url settings:settings error:&error];
    if (!recorder)
    {
        NSLog(@"Error establishing recorder: %@", error.localizedFailureReason);
        return nil;
    }

    recorder.delegate = self;
    if (![recorder prepareToRecord])
    {
        NSLog(@"Error: Prepare to record failed");
        //[self say:@"Error while preparing recording"];
        return nil;
    }

    if (![recorder record])
    {
        NSLog(@"Error: Record failed");
    //  [self say:@"Error while attempting to record audio"];
        return nil;
    }

    return recorder;
}

- (void) stopAudioRecording {
    if (nil != self.avAudioRecorder) {
        [self.avAudioRecorder stop];
    }
}

- (void) playRecording:(NSURL *) url
{
    NSLog(@"playRecording");
    // Init audio with playback capability
    AVAudioSession *audioSession = [AVAudioSession sharedInstance];
    [audioSession setCategory:AVAudioSessionCategoryPlayback error:nil];
    NSError *error;
    AVAudioPlayer *audioPlayer = [[AVAudioPlayer alloc] initWithContentsOfURL:url error:&error];
    audioPlayer.numberOfLoops = 0;
    [audioPlayer play];
    NSLog(@"playing");
}

- (void)stopListening:(CDVInvokedUrlCommand*)command {
    [self.commandDelegate runInBackground:^{
        NSLog(@"stopListening()");

        if ( self.audioEngine.isRunning ) {
            // Restore sound settings.
            AVAudioSession *audioSession = [AVAudioSession sharedInstance];
            [audioSession setCategory:AVAudioSessionCategoryPlayback error:nil];
            [audioSession setMode:AVAudioSessionModeDefault error:nil];
            [audioSession setActive:YES withOptions:AVAudioSessionSetActiveOptionNotifyOthersOnDeactivation error:nil];
        }

        [self.audioEngine stop];
        if (nil != self.recognitionRequest) {
            [self.recognitionRequest endAudio];
        }
        [self stopAudioRecording];

        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsString:self.avAudioRecorder.url.absoluteString];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    }];
}

- (void)getSupportedLanguages:(CDVInvokedUrlCommand*)command {
    NSSet<NSLocale *> *supportedLocales = [SFSpeechRecognizer supportedLocales];

    NSMutableArray *localesArray = [[NSMutableArray alloc] init];

    for(NSLocale *locale in supportedLocales) {
        [localesArray addObject:[locale localeIdentifier]];
    }

    CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsArray:localesArray];
    [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
}

- (void)hasPermission:(CDVInvokedUrlCommand*)command {
    SFSpeechRecognizerAuthorizationStatus status = [SFSpeechRecognizer authorizationStatus];
    BOOL speechAuthGranted = (status == SFSpeechRecognizerAuthorizationStatusAuthorized);

    if (!speechAuthGranted) {
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsBool:NO];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
        return;
    }

    [[AVAudioSession sharedInstance] requestRecordPermission:^(BOOL granted){
        CDVPluginResult *pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK messageAsBool:granted];
        [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
    }];
}

- (void)requestPermission:(CDVInvokedUrlCommand*)command {
    [SFSpeechRecognizer requestAuthorization:^(SFSpeechRecognizerAuthorizationStatus status){
        dispatch_async(dispatch_get_main_queue(), ^{
            CDVPluginResult *pluginResult = nil;
            BOOL speechAuthGranted = NO;

            switch (status) {
                case SFSpeechRecognizerAuthorizationStatusAuthorized:
                    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
                    speechAuthGranted = YES;
                    break;
                case SFSpeechRecognizerAuthorizationStatusDenied:
                    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:MESSAGE_ACCESS_DENIED];
                    break;
                case SFSpeechRecognizerAuthorizationStatusRestricted:
                    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:MESSAGE_RESTRICTED];
                    break;
                case SFSpeechRecognizerAuthorizationStatusNotDetermined:
                    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:MESSAGE_NOT_DETERMINED];
                    break;
            }

            if (!speechAuthGranted) {
                [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
                return;
            }

            [[AVAudioSession sharedInstance] requestRecordPermission:^(BOOL granted){
                CDVPluginResult *pluginResult = nil;

                if (granted) {
                    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_OK];
                } else {
                    pluginResult = [CDVPluginResult resultWithStatus:CDVCommandStatus_ERROR messageAsString:MESSAGE_ACCESS_DENIED_MICROPHONE];
                }

                [self.commandDelegate sendPluginResult:pluginResult callbackId:command.callbackId];
            }];
        });
    }];
}

@end
