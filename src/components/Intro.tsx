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

/** Abertura animada do app: logo, nome sendo "digitado", linha de energia e "Carregando". */
export function Intro({ done, onFinish }: { done: boolean; onFinish: () => void }) {
  const ins = useSafeAreaInsets();
  const enter = useRef(new Animated.Value(0)).current;
  const line = useRef(new Animated.Value(0)).current;
  const out = useRef(new Animated.Value(0)).current;
  const [typed, setTyped] = useState(0);
  const [dots, setDots] = useState(0);
  const [minTime, setMinTime] = useState(false);
  const word = BRAND.name.toUpperCase();

  useEffect(() => {
    Animated.timing(enter, { toValue: 1, duration: 650, easing: Easing.out(Easing.back(1.6)), useNativeDriver: true }).start();
    const l = Animated.loop(
      Animated.sequence([
        Animated.timing(line, { toValue: 1, duration: 1100, easing: Easing.inOut(Easing.cubic), useNativeDriver: true }),
        Animated.timing(line, { toValue: 0, duration: 0, useNativeDriver: true }),
      ])
    );
    l.start();
    let i = 0;
    const type = setInterval(() => {
      i += 1;
      setTyped(i);
      if (i >= word.length) clearInterval(type);
    }, 75);
    const dot = setInterval(() => setDots((d) => (d + 1) % 4), 380);
    const t = setTimeout(() => setMinTime(true), 1900);
    return () => {
      l.stop();
      clearInterval(type);
      clearInterval(dot);
      clearTimeout(t);
    };
  }, [enter, line, word.length]);

  useEffect(() => {
    if (done && minTime) {
      Animated.timing(out, { toValue: 1, duration: 480, easing: Easing.in(Easing.cubic), useNativeDriver: true }).start(() => onFinish());
    }
  }, [done, minTime, out, onFinish]);

  const enterScale = enter.interpolate({ inputRange: [0, 1], outputRange: [0.6, 1] });
  const outScale = out.interpolate({ inputRange: [0, 1], outputRange: [1, 1.35] });
  const fade = out.interpolate({ inputRange: [0, 1], outputRange: [1, 0] });
  const lineX = line.interpolate({ inputRange: [0, 1], outputRange: [-170, 170] });

  return (
    <Animated.View style={[FILL, st.wrap, { opacity: fade }]} pointerEvents="none">
      <LinearGradient colors={[A(0.22), A(0.05), 'rgba(0,0,0,0)']} style={[FILL, { height: '60%' }]} />
      <Animated.View style={{ alignItems: 'center', opacity: enter, transform: [{ scale: Animated.multiply(enterScale, outScale) }] }}>
        <BrandLogo size={128} />
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
  word: { color: C.text, fontSize: 22, fontWeight: '900', letterSpacing: 6, marginTop: 18, minHeight: 30 },
  track: { width: 170, height: 2, borderRadius: 2, backgroundColor: 'rgba(255,255,255,0.08)', overflow: 'hidden', marginTop: 18 },
  beam: { position: 'absolute', top: 0, bottom: 0, width: 120, left: 25 },
  loading: { color: C.muted, fontSize: 13, letterSpacing: 1.5, marginTop: 16 },
  copy: { position: 'absolute', color: C.dim, fontSize: 10.5, letterSpacing: 1.2, textAlign: 'center' },
});
