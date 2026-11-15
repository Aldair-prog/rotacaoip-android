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
    sound: 'connect_notificacao.wav',
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
    sound: 'stratton_notificacao.wav',
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
            enableMinifyInReleaseBuilds: true,
            enableShrinkResourcesInReleaseBuilds: true,
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
