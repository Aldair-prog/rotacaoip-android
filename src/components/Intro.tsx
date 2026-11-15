import React, { useEffect, useRef, useState } from 'react';
import { Animated, Easing, StyleSheet, Text, View } from 'react-native';
import { Image } from 'expo-image';
import { LinearGradient } from 'expo-linear-gradient';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { C, FILL } from '../theme';
import { A, BRAND, LOGO, LOGO_RATIO } from '../brand';

const loop = (v: Animated.Value, duration: number, easing = Easing.linear) =>
  Animated.loop(Animated.timing(v, { toValue: 1, duration, easing, useNativeDriver: true }));

/** Logo da marca com aura pulsando, anéis de scanner girando e um reflexo passando. */
export function BrandLogo({ size = 132, rings = true }: { size?: number; rings?: boolean }) {
  const spin = useRef(new Animated.Value(0)).current;
  const pulse = useRef(new Animated.Value(0)).current;
  const shine = useRef(new Animated.Value(0)).current;

  useEffect(() => {
    const a = loop(spin, 2600);
    const b = Animated.loop(
      Animated.sequence([
        Animated.timing(pulse, { toValue: 1, duration: 1300, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
        Animated.timing(pulse, { toValue: 0, duration: 1300, easing: Easing.inOut(Easing.sin), useNativeDriver: true }),
      ])
    );
    const c = Animated.loop(
      Animated.sequence([
        Animated.delay(500),
        Animated.timing(shine, { toValue: 1, duration: 1100, easing: Easing.inOut(Easing.quad), useNativeDriver: true }),
        Animated.timing(shine, { toValue: 0, duration: 0, useNativeDriver: true }),
        Animated.delay(1400),
      ])
    );
    a.start();
    b.start();
    c.start();
    return () => {
      a.stop();
      b.stop();
      c.stop();
    };
  }, [spin, pulse, shine]);

  const ring = size * 1.55;
  const rotate = spin.interpolate({ inputRange: [0, 1], outputRange: ['0deg', '360deg'] });
  const rotateBack = spin.interpolate({ inputRange: [0, 1], outputRange: ['360deg', '0deg'] });
  const auraScale = pulse.interpolate({ inputRange: [0, 1], outputRange: [0.9, 1.12] });
  const auraOpacity = pulse.interpolate({ inputRange: [0, 1], outputRange: [0.45, 0.9] });
  const logoScale = pulse.interpolate({ inputRange: [0, 1], outputRange: [1, 1.035] });
  const shineX = shine.interpolate({ inputRange: [0, 1], outputRange: [-size * 1.2, size * 1.2] });

  return (
    <View style={{ width: ring, height: ring, alignItems: 'center', justifyContent: 'center' }}>
      {/* aura */}
      <Animated.View
        pointerEvents="none"
        style={[
          st.aura,
          { width: ring * 1.25, height: ring * 1.25, borderRadius: ring, opacity: auraOpacity, transform: [{ scale: auraScale }] },
        ]}
      >
        {/* várias camadas bem fracas = brilho suave, sem borda dura */}
        {[0, 0.06, 0.12, 0.18, 0.24, 0.3, 0.36].map((m) => (
          <View key={m} style={[st.auraIn, { margin: ring * m, borderRadius: ring, backgroundColor: A(0.045) }]} />
        ))}
      </Animated.View>

      {rings ? (
        <>
          <Animated.View
            pointerEvents="none"
            style={[
              st.ring,
              {
                width: ring,
                height: ring,
                borderRadius: ring / 2,
                borderTopColor: C.red,
                borderRightColor: A(0.55),
                transform: [{ rotate }],
              },
            ]}
          />
          <Animated.View
            pointerEvents="none"
            style={[
              st.ring,
              st.dashed,
              { width: ring * 0.86, height: ring * 0.86, borderRadius: ring, transform: [{ rotate: rotateBack }] },
            ]}
          />
        </>
      ) : null}

      {/* logo + reflexo */}
      <Animated.View
        style={{
          width: size,
          height: size / LOGO_RATIO,
          overflow: 'hidden',
          borderRadius: BRAND.id === 'stratton' ? size / 2 : size * 0.22,
          transform: [{ scale: logoScale }],
        }}
      >
        <Image source={LOGO} style={{ width: '100%', height: '100%' }} contentFit="contain" />
        <Animated.View pointerEvents="none" style={[FILL, { transform: [{ translateX: shineX }, { rotate: '20deg' }] }]}>
          <LinearGradient
            colors={['rgba(255,255,255,0)', 'rgba(255,255,255,0.22)', 'rgba(255,255,255,0)']}
            start={{ x: 0, y: 0.5 }}
            end={{ x: 1, y: 0.5 }}
            style={{ position: 'absolute', top: -size, bottom: -size, width: size * 0.35, left: size * 0.32 }}
          />
        </Animated.View>
      </Animated.View>
    </View>
  );
}

// Partículas que vêm de fora e se juntam na logo (posições fixas para não pesar)
const PARTS = Array.from({ length: 22 }, (_, i) => {
  const ang = (i / 22) * Math.PI * 2 + (i % 3) * 0.35;
  const dist = 190 + ((i * 37) % 120);
  return { x: Math.cos(ang) * dist, y: Math.sin(ang) * dist, size: 3 + (i % 4), delay: (i % 7) * 45, white: i % 3 === 0 };
});

/** Abertura animada do app: partículas, explosão de luz, logo girando em 3D, nome digitado e linha de energia. */
export function Intro({ done, onFinish }: { done: boolean; onFinish: () => void }) {
  const ins = useSafeAreaInsets();
  const gather = useRef(new Animated.Value(0)).current; // partículas indo para o centro
  const flip = useRef(new Animated.Value(0)).current; // logo entrando em 3D
  const boom = useRef(new Animated.Value(0)).current; // onda de choque + clarão
  const sweep = useRef(new Animated.Value(0)).current; // feixes de luz cruzando
  const line = useRef(new Animated.Value(0)).current;
  const out = useRef(new Animated.Value(0)).current;
  const [typed, setTyped] = useState(0);
  const [dots, setDots] = useState(0);
  const [minTime, setMinTime] = useState(false);
  const word = BRAND.name.toUpperCase();

  useEffect(() => {
    Animated.parallel([
      Animated.timing(gather, { toValue: 1, duration: 700, easing: Easing.in(Easing.cubic), useNativeDriver: true }),
      Animated.timing(sweep, { toValue: 1, duration: 900, easing: Easing.inOut(Easing.quad), useNativeDriver: true }),
      Animated.sequence([
        Animated.delay(380),
        Animated.spring(flip, { toValue: 1, friction: 6, tension: 60, useNativeDriver: true }),
      ]),
      Animated.sequence([
        Animated.delay(640),
        Animated.timing(boom, { toValue: 1, duration: 750, easing: Easing.out(Easing.cubic), useNativeDriver: true }),
      ]),
    ]).start();
    const l = Animated.loop(
      Animated.sequence([
        Animated.timing(line, { toValue: 1, duration: 1100, easing: Easing.inOut(Easing.cubic), useNativeDriver: true }),
        Animated.timing(line, { toValue: 0, duration: 0, useNativeDriver: true }),
      ])
    );
    l.start();
    let i = 0;
    let type: ReturnType<typeof setInterval> | undefined;
    const startType = setTimeout(() => {
      type = setInterval(() => {
        i += 1;
        setTyped(i);
        if (i >= word.length && type) clearInterval(type);
      }, 65);
    }, 750);
    const dot = setInterval(() => setDots((d) => (d + 1) % 4), 380);
    const t = setTimeout(() => setMinTime(true), 2300);
    return () => {
      l.stop();
      clearTimeout(startType);
      if (type) clearInterval(type);
      clearInterval(dot);
      clearTimeout(t);
    };
  }, [gather, flip, boom, sweep, line, word.length]);

  useEffect(() => {
    if (done && minTime) {
      Animated.timing(out, { toValue: 1, duration: 520, easing: Easing.in(Easing.cubic), useNativeDriver: true }).start(() => onFinish());
    }
  }, [done, minTime, out, onFinish]);

  const rotateY = flip.interpolate({ inputRange: [0, 1], outputRange: ['-180deg', '0deg'] });
  const flipScale = flip.interpolate({ inputRange: [0, 1], outputRange: [0.25, 1] });
  const outScale = out.interpolate({ inputRange: [0, 1], outputRange: [1, 1.45] });
  const fade = out.interpolate({ inputRange: [0, 1], outputRange: [1, 0] });
  const ringScale = boom.interpolate({ inputRange: [0, 1], outputRange: [0.3, 3.2] });
  const ringOpacity = boom.interpolate({ inputRange: [0, 0.15, 1], outputRange: [0, 0.9, 0] });
  const flash = boom.interpolate({ inputRange: [0, 0.08, 0.5], outputRange: [0, 0.32, 0], extrapolate: 'clamp' });
  const lineX = line.interpolate({ inputRange: [0, 1], outputRange: [-170, 170] });
  const beamA = sweep.interpolate({ inputRange: [0, 1], outputRange: [-500, 500] });
  const beamB = sweep.interpolate({ inputRange: [0, 1], outputRange: [500, -500] });
  const beamOpacity = sweep.interpolate({ inputRange: [0, 0.3, 0.7, 1], outputRange: [0, 0.7, 0.7, 0] });
  const textIn = boom.interpolate({ inputRange: [0, 0.4, 1], outputRange: [0, 0, 1] });

  return (
    <Animated.View style={[FILL, st.wrap, { opacity: fade }]} pointerEvents="none">
      <LinearGradient colors={[A(0.28), A(0.06), 'rgba(0,0,0,0)']} style={[FILL, { height: '65%' }]} />

      {/* feixes de luz cruzando a tela */}
      <Animated.View style={[st.beam2, { opacity: beamOpacity, transform: [{ translateX: beamA }, { rotate: '-28deg' }] }]}>
        <LinearGradient colors={['rgba(0,0,0,0)', A(0.85), 'rgba(255,255,255,0.9)', A(0.85), 'rgba(0,0,0,0)']} start={{ x: 0, y: 0.5 }} end={{ x: 1, y: 0.5 }} style={{ flex: 1 }} />
      </Animated.View>
      <Animated.View style={[st.beam2, { opacity: beamOpacity, transform: [{ translateX: beamB }, { rotate: '28deg' }] }]}>
        <LinearGradient colors={['rgba(0,0,0,0)', A(0.6), 'rgba(255,255,255,0.7)', A(0.6), 'rgba(0,0,0,0)']} start={{ x: 0, y: 0.5 }} end={{ x: 1, y: 0.5 }} style={{ flex: 1 }} />
      </Animated.View>

      {/* partículas se juntando */}
      {PARTS.map((p, k) => {
        const tx = gather.interpolate({ inputRange: [0, 1], outputRange: [p.x, 0] });
        const ty = gather.interpolate({ inputRange: [0, 1], outputRange: [p.y, 0] });
        const op = gather.interpolate({ inputRange: [0, 0.2, 0.9, 1], outputRange: [0, 1, 1, 0] });
        return (
          <Animated.View
            key={k}
            style={{
              position: 'absolute',
              width: p.size,
              height: p.size,
              borderRadius: p.size,
              backgroundColor: p.white ? '#FFFFFF' : C.redLight,
              opacity: op,
              transform: [{ translateX: tx }, { translateY: ty }],
            }}
          />
        );
      })}

      {/* onda de choque quando a logo chega */}
      <Animated.View style={[st.shock, { opacity: ringOpacity, transform: [{ scale: ringScale }] }]} />
      <Animated.View style={[st.shock, { borderWidth: 1, opacity: ringOpacity, transform: [{ scale: Animated.multiply(ringScale, 0.7) }] }]} />

      <Animated.View style={{ alignItems: 'center', transform: [{ scale: outScale }] }}>
        <Animated.View style={{ transform: [{ perspective: 900 }, { rotateY }, { scale: flipScale }] }}>
          <BrandLogo size={132} />
        </Animated.View>
        <Animated.View style={{ alignItems: 'center', opacity: textIn }}>
          <Text style={st.word}>
            {word.slice(0, typed)}
            <Text style={{ color: C.red }}>{typed < word.length ? '▌' : ''}</Text>
          </Text>
          <View style={st.track}>
            <Animated.View style={[st.beam, { transform: [{ translateX: lineX }] }]}>
              <LinearGradient
                colors={['rgba(0,0,0,0)', C.red, C.redLight, C.red, 'rgba(0,0,0,0)']}
                start={{ x: 0, y: 0.5 }}
                end={{ x: 1, y: 0.5 }}
                style={{ flex: 1 }}
              />
            </Animated.View>
          </View>
          <Text style={st.loading}>
            Carregando<Text style={{ color: C.red }}>{'.'.repeat(dots)}</Text>
            <Text style={{ color: 'transparent' }}>{'.'.repeat(3 - dots)}</Text>
          </Text>
        </Animated.View>
      </Animated.View>

      {/* clarão */}
      <Animated.View style={[FILL, { backgroundColor: '#FFFFFF', opacity: flash }]} />

      <Text style={[st.copy, { bottom: ins.bottom + 22 }]}>
        © {new Date().getFullYear()} {BRAND.copyright}
      </Text>
    </Animated.View>
  );
}

const st = StyleSheet.create({
  wrap: { backgroundColor: C.bg, alignItems: 'center', justifyContent: 'center', zIndex: 999, elevation: 999 },
  aura: { position: 'absolute', alignItems: 'stretch', justifyContent: 'center' },
  auraIn: { position: 'absolute', top: 0, left: 0, right: 0, bottom: 0 },
  ring: { position: 'absolute', borderWidth: 2, borderColor: 'rgba(255,255,255,0.06)' },
  dashed: { borderWidth: 1, borderStyle: 'dashed', borderColor: 'rgba(255,255,255,0.16)' },
  word: {
    color: C.text,
    fontSize: 24,
    fontWeight: '900',
    letterSpacing: 7,
    marginTop: 18,
    minHeight: 32,
    textShadowColor: A(0.9),
    textShadowRadius: 14,
    textShadowOffset: { width: 0, height: 0 },
  },
  shock: { position: 'absolute', width: 180, height: 180, borderRadius: 90, borderWidth: 3, borderColor: C.redLight },
  beam2: { position: 'absolute', width: 420, height: 3, borderRadius: 3 },
  track: { width: 170, height: 2, borderRadius: 2, backgroundColor: 'rgba(255,255,255,0.08)', overflow: 'hidden', marginTop: 18 },
  beam: { position: 'absolute', top: 0, bottom: 0, width: 120, left: 25 },
  loading: { color: C.muted, fontSize: 13, letterSpacing: 1.5, marginTop: 16 },
  copy: { position: 'absolute', color: C.dim, fontSize: 10.5, letterSpacing: 1.2, textAlign: 'center' },
});
