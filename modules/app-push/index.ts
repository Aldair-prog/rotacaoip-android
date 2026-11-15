import { Platform } from 'react-native';
import { requireOptionalNativeModule } from 'expo-modules-core';

// Push do Android sem Firebase (módulo nativo em ./android). No iPhone não existe: lá o push vai pelo servidor da Apple.
type Subscription = { endpoint: string; publicKey: string; authenticationSecret: string };

type AppPushNative = {
  configure(brandName: string, color: string, sound: string, scheme: string): void;
  getSubscription(): Subscription;
  setSubscribed(on: boolean): void;
  isSubscribed(): boolean;
  ensure(): void;
  reset(): void;
  notify(title: string, body: string): void;
  sendTest(): Promise<boolean>;
  isIgnoringBatteryOptimizations(): boolean;
  requestIgnoreBatteryOptimizations(): void;
};

export const AppPush: AppPushNative | null =
  Platform.OS === 'android' ? requireOptionalNativeModule<AppPushNative>('AppPush') : null;
