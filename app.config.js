// Um código só para os 4 apps: STRATTON e Connect Pay, Android e iPhone.
// A marca vem da variável APP_BRAND (stratton | connect). Padrão: connect.
const BRAND = process.env.APP_BRAND === 'stratton' ? 'stratton' : 'connect';
const BUILD = Number(process.env.BUILD_NUMBER || 1);

const BRANDS = {
  connect: {
    name: 'Connect Pay',
    slug: 'connect-pay',
    scheme: 'connectpay',
    id: 'br.com.connectmdcpay.app',
    color: '#F4212E',
    iconBg: '#000000',
    sound: 'connect_dinheiro.wav',
    web: 'https://connectmdcpay.com.br',
    eas: { projectId: '1eca7bc8-52b0-440c-b06e-e9a2488be504', owner: 'ctdf5645c5s4434533gtyicdfx' },
  },
  stratton: {
    name: 'STRATTON',
    slug: 'stratton-pay',
    scheme: 'strattonpay',
    id: 'br.com.strattonpay.app',
    color: '#8B3DFF',
    iconBg: '#FFFFFF',
    sound: 'stratton_dinheiro.wav',
    web: 'https://strattonpay.com.br',
    eas: null, // projeto Expo da Stratton ainda não criado (só precisa para o iPhone)
  },
};

const b = BRANDS[BRAND];
const dir = `./brands/${BRAND}`;
const faceId = `Use o Face ID para desbloquear o ${b.name} e confirmar envios de Pix.`;
const camera = 'A câmera é usada para ler QR Codes Pix na hora de pagar.';

module.exports = {
  expo: {
    name: b.name,
    slug: b.slug,
    version: `2.0.${BUILD}`,
    orientation: 'portrait',
    icon: `${dir}/icon.png`,
    scheme: b.scheme,
    userInterfaceStyle: 'dark',
    backgroundColor: '#000000',
    newArchEnabled: true,
    ios: {
      bundleIdentifier: b.id,
      buildNumber: String(BUILD),
      supportsTablet: false,
      config: { usesNonExemptEncryption: false },
      // Manifesto de privacidade exigido pela Apple (APIs usadas pelo React Native/Expo e sem rastreamento)
      privacyManifests: {
        NSPrivacyTracking: false,
        NSPrivacyTrackingDomains: [],
        NSPrivacyCollectedDataTypes: [],
        NSPrivacyAccessedAPITypes: [
          { NSPrivacyAccessedAPIType: 'NSPrivacyAccessedAPICategoryUserDefaults', NSPrivacyAccessedAPITypeReasons: ['CA92.1'] },
          { NSPrivacyAccessedAPIType: 'NSPrivacyAccessedAPICategoryFileTimestamp', NSPrivacyAccessedAPITypeReasons: ['0A2A.1', '3B52.1', 'C617.1'] },
          { NSPrivacyAccessedAPIType: 'NSPrivacyAccessedAPICategoryDiskSpace', NSPrivacyAccessedAPITypeReasons: ['E174.1', '85F4.1'] },
          { NSPrivacyAccessedAPIType: 'NSPrivacyAccessedAPICategorySystemBootTime', NSPrivacyAccessedAPITypeReasons: ['35F9.1'] },
        ],
      },
      infoPlist: {
        CFBundleDevelopmentRegion: 'pt-BR',
        CFBundleLocalizations: ['pt-BR'],
        NSFaceIDUsageDescription: faceId,
        NSCameraUsageDescription: camera,
        ITSAppUsesNonExemptEncryption: false,
      },
    },
    android: {
      package: b.id,
      versionCode: BUILD,
      adaptiveIcon: { foregroundImage: `${dir}/adaptive-icon.png`, backgroundColor: b.iconBg },
      softwareKeyboardLayoutMode: 'resize',
      allowBackup: false,
      permissions: [
        'android.permission.USE_BIOMETRIC',
        'android.permission.USE_FINGERPRINT',
        'android.permission.CAMERA',
        'android.permission.POST_NOTIFICATIONS',
      ],
      blockedPermissions: ['android.permission.RECORD_AUDIO', 'android.permission.SYSTEM_ALERT_WINDOW'],
    },
    plugins: [
      'expo-router',
      'expo-secure-store',
      'expo-sharing',
      [
        'expo-notifications',
        { icon: `${dir}/notification-icon.png`, color: b.color, sounds: [`${dir}/${b.sound}`] },
      ],
      [
        'expo-splash-screen',
        { image: `${dir}/splash-icon.png`, imageWidth: 170, resizeMode: 'contain', backgroundColor: '#000000' },
      ],
      ['expo-local-authentication', { faceIDPermission: faceId }],
      ['expo-camera', { cameraPermission: camera, recordAudioAndroid: false }],
      [
        'expo-build-properties',
        {
          android: {
            // o Expo 58 já mira a API mais nova do Android (acima da 36 exigida pela Play Store)
            usesCleartextTraffic: false,
            enableMinifyInReleaseBuilds: true,
            enableShrinkResourcesInReleaseBuilds: true,
            // regras para o otimizador não apagar o que o push nativo usa (WorkManager/Room e o cofre criptografado)
            extraProguardRules: [
              '-keep class androidx.work.** { *; }',
              '-keep class * extends androidx.room.RoomDatabase { <init>(); }',
              '-keep class androidx.room.** { *; }',
              '-keep class expo.modules.apppush.** { *; }',
              '-keep class com.google.crypto.tink.** { *; }',
              '-keep class androidx.security.crypto.** { *; }',
              '-dontwarn com.google.errorprone.annotations.**',
              '-dontwarn javax.annotation.**',
              '-dontwarn com.google.api.client.**',
              '-dontwarn org.joda.time.**',
              '-dontwarn com.google.crypto.tink.**',
            ].join('\n'),
          },
        },
      ],
    ],
    extra: {
      brand: BRAND,
      privacyUrl: `${b.web}/privacidade`,
      termsUrl: `${b.web}/termos`,
      supportUrl: b.web,
      router: { asyncRoutes: { web: true } },
      ...(b.eas ? { eas: { projectId: b.eas.projectId } } : {}),
    },
    ...(b.eas ? { owner: b.eas.owner } : {}),
  },
};
