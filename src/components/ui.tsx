import React, { useCallback, useEffect, useRef, useState } from 'react';
import { useFocusEffect } from 'expo-router';
import {
  Animated,
  Easing,
  ActivityIndicator,
  Pressable,
  RefreshControl,
  ScrollView,
  StyleProp,
  StyleSheet,
  Text,
  TextStyle,
  View,
  ViewStyle,
} from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import * as Haptics from 'expo-haptics';
import { Ionicons } from '@expo/vector-icons';
import { C, R } from '../theme';
import { brl } from '../lib/format';
import { useSession } from '../session';
import type { TxStatus, WithdrawStatus } from '../lib/api';
import { A } from '../brand';
import { E2E } from '../e2e';

export type IconName = keyof typeof Ionicons.glyphMap;

export const tap = () => Haptics.selectionAsync().catch(() => {});
export const impact = () => Haptics.impactAsync(Haptics.ImpactFeedbackStyle.Light).catch(() => {});
export const success = () => Haptics.notificationAsync(Haptics.NotificationFeedbackType.Success).catch(() => {});
export const failure = () => Haptics.notificationAsync(Haptics.NotificationFeedbackType.Error).catch(() => {});

/** Brilho vermelho suave no topo das telas */
export function Glow({ height = 380, strength = 0.26 }: { height?: number; strength?: number }) {
  return (
    <LinearGradient
      pointerEvents="none"
      colors={[A(strength), A(strength / 4), 'rgba(0,0,0,0)']}
      style={{ position: 'absolute', top: 0, left: 0, right: 0, height }}
    />
  );
}

/** Puxar pra atualizar no padrão da Apple, guardando o estado de "atualizando" */
export function useRefresh(onRefresh?: () => unknown) {
  const [refreshing, setRefreshing] = useState(false);
  const [stamp, setStamp] = useState(0);
  const run = useCallback(async () => {
    if (!onRefresh) return;
    setRefreshing(true);
    impact();
    if (E2E) console.log('PULL_REFRESH');
    try {
      // Tempo mínimo pra dar pra ver que recarregou
      await Promise.all([onRefresh(), new Promise((ok) => setTimeout(ok, 600))]);
    } finally {
      setRefreshing(false);
      setStamp((n) => n + 1);
      success();
    }
  }, [onRefresh]);
  return { refreshing, run, stamp };
}

/** Selo "Atualizado" que desce no topo depois de puxar pra atualizar */
export function RefreshPill({ stamp, top }: { stamp: number; top: number }) {
  const a = useRef(new Animated.Value(0)).current;
  useEffect(() => {
    if (!stamp) return;
    a.setValue(0);
    Animated.sequence([
      Animated.spring(a, { toValue: 1, useNativeDriver: true, speed: 16, bounciness: 9 }),
      Animated.delay(1100),
      Animated.timing(a, { toValue: 0, duration: 260, useNativeDriver: true }),
    ]).start();
  }, [stamp, a]);
  return (
    <Animated.View
      pointerEvents="none"
      style={[
        s.pill,
        { top, opacity: a, transform: [{ translateY: a.interpolate({ inputRange: [0, 1], outputRange: [-30, 0] }) }, { scale: a.interpolate({ inputRange: [0, 1], outputRange: [0.85, 1] }) }] },
      ]}
    >
      <Ionicons name="checkmark-circle" size={17} color={C.green} />
      <Text style={s.pillText}>Atualizado agora</Text>
    </Animated.View>
  );
}

export function Screen({
  children,
  onRefresh,
  bottomPad = 120,
  glow = true,
}: {
  children: React.ReactNode;
  refreshing?: boolean;
  onRefresh?: () => unknown;
  bottomPad?: number;
  glow?: boolean;
}) {
  const ins = useSafeAreaInsets();
  const r = useRefresh(onRefresh);
  // Entrada da tela: aparece subindo de leve toda vez que a aba é aberta
  const enter = useRef(new Animated.Value(0)).current;
  useFocusEffect(
    useCallback(() => {
      enter.setValue(0);
      Animated.timing(enter, { toValue: 1, duration: 340, easing: Easing.out(Easing.cubic), useNativeDriver: true }).start();
    }, [enter])
  );
  // Depois de puxar pra atualizar, a tela "renasce": entra de novo com o conteúdo novo
  useEffect(() => {
    if (!r.stamp) return;
    enter.setValue(0.2);
    Animated.timing(enter, { toValue: 1, duration: 420, easing: Easing.out(Easing.cubic), useNativeDriver: true }).start();
  }, [r.stamp, enter]);
  const enterStyle = {
    opacity: enter,
    transform: [{ translateY: enter.interpolate({ inputRange: [0, 1], outputRange: [14, 0] }) }],
  };
  return (
    <View style={{ flex: 1, backgroundColor: C.bg }}>
      {glow && <Glow />}
      <ScrollView
        showsVerticalScrollIndicator={false}
        keyboardShouldPersistTaps="handled"
        keyboardDismissMode="interactive"
        contentInsetAdjustmentBehavior="never"
        automaticallyAdjustContentInsets={false}
        automaticallyAdjustKeyboardInsets
        style={{ marginTop: ins.top }}
        contentContainerStyle={{ paddingTop: 10, paddingBottom: ins.bottom + bottomPad, paddingHorizontal: 20 }}
        refreshControl={
          onRefresh ? (
            <RefreshControl tintColor={C.red} colors={[C.red]} progressBackgroundColor="#2C2D35" size="large" refreshing={r.refreshing} onRefresh={r.run} />
          ) : undefined
        }
      >
        <Animated.View style={enterStyle}>{children}</Animated.View>
      </ScrollView>
      <RefreshPill stamp={r.stamp} top={ins.top + 8} />
    </View>
  );
}

export function Header({ title, subtitle, right }: { title: string; subtitle?: string; right?: React.ReactNode }) {
  return (
    <View style={{ flexDirection: 'row', alignItems: 'flex-end', marginBottom: 18, marginTop: 6 }}>
      <View style={{ flex: 1 }}>
        <Text style={s.h1}>{title}</Text>
        {subtitle ? <Text style={s.subtitle}>{subtitle}</Text> : null}
      </View>
      {right}
    </View>
  );
}

export function Card({ children, style }: { children: React.ReactNode; style?: StyleProp<ViewStyle> }) {
  return <View style={[s.card, style]}>{children}</View>;
}

export function SectionTitle({ title, action, onAction }: { title: string; action?: string; onAction?: () => void }) {
  return (
    <View style={s.sectionRow}>
      <Text style={s.section}>{title}</Text>
      {action ? (
        <Pressable hitSlop={10} onPress={onAction}>
          <Text style={s.sectionAction}>{action}</Text>
        </Pressable>
      ) : null}
    </View>
  );
}

export function Button({
  title,
  onPress,
  loading,
  disabled,
  variant = 'primary',
  icon,
  style,
}: {
  title: string;
  onPress?: () => void;
  loading?: boolean;
  disabled?: boolean;
  variant?: 'primary' | 'secondary' | 'danger' | 'plain';
  icon?: IconName;
  style?: StyleProp<ViewStyle>;
}) {
  const off = disabled || loading;
  const bg =
    variant === 'primary' ? C.red : variant === 'secondary' ? C.card2 : variant === 'danger' ? C.redSoft : 'transparent';
  const fg = variant === 'danger' ? C.redLight : variant === 'plain' ? C.redLight : C.text;
  return (
    <Pressable
      accessibilityRole="button"
      disabled={off}
      onPress={() => {
        impact();
        onPress?.();
      }}
      style={({ pressed }) => [
        s.btn,
        { backgroundColor: bg, opacity: off ? 0.45 : pressed ? 0.82 : 1, transform: [{ scale: pressed ? 0.985 : 1 }] },
        variant === 'secondary' && { borderWidth: StyleSheet.hairlineWidth, borderColor: C.borderStrong },
        style,
      ]}
    >
      {loading ? (
        <ActivityIndicator color={fg} />
      ) : (
        <View style={{ flexDirection: 'row', alignItems: 'center', gap: 8 }}>
          {icon ? <Ionicons name={icon} size={19} color={fg} /> : null}
          <Text style={[s.btnText, { color: fg }]}>{title}</Text>
        </View>
      )}
    </Pressable>
  );
}

export function IconButton({ icon, onPress, label }: { icon: IconName; onPress: () => void; label: string }) {
  return (
    <Pressable
      accessibilityLabel={label}
      hitSlop={8}
      onPress={() => {
        tap();
        onPress();
      }}
      style={({ pressed }) => [s.iconBtn, { opacity: pressed ? 0.7 : 1 }]}
    >
      <Ionicons name={icon} size={20} color={C.text} />
    </Pressable>
  );
}

/** Valor em reais que respeita o "ocultar valores" */
export function Money({
  cents,
  style,
  sign,
  forceShow,
}: {
  cents: number | null | undefined;
  style?: StyleProp<TextStyle>;
  sign?: '+' | '-';
  forceShow?: boolean;
}) {
  const { hideValues } = useSession();
  const text = hideValues && !forceShow ? 'R$ ••••' : `${sign ? `${sign} ` : ''}${brl(cents)}`;
  return (
    <Text style={[{ color: C.text, fontVariant: ['tabular-nums'] }, style]} numberOfLines={1} adjustsFontSizeToFit>
      {text}
    </Text>
  );
}

export function Badge({ label, color, bg }: { label: string; color: string; bg: string }) {
  return (
    <View style={[s.badge, { backgroundColor: bg }]}>
      <Text style={[s.badgeText, { color }]}>{label}</Text>
    </View>
  );
}

type SInfo = { label: string; color: string; bg: string; icon: IconName };

export function txStatus(st?: TxStatus): SInfo {
  switch (st) {
    case 'APPROVED':
      return { label: 'Pago', color: C.green, bg: C.greenSoft, icon: 'checkmark-circle' };
    case 'PENDING':
    case 'PROCESSING':
      return { label: 'Aguardando', color: C.orange, bg: C.orangeSoft, icon: 'time' };
    case 'REFUNDED':
      return { label: 'Estornado', color: C.muted, bg: 'rgba(142,142,147,0.16)', icon: 'arrow-undo' };
    case 'CANCELLED':
      return { label: 'Cancelado', color: C.muted, bg: 'rgba(142,142,147,0.16)', icon: 'close-circle' };
    case 'IN_PROTEST':
    case 'PRE_CHARGEBACK':
      return { label: 'Em disputa', color: C.orange, bg: C.orangeSoft, icon: 'alert-circle' };
    case 'CHARGEBACK':
      return { label: 'Chargeback', color: C.redLight, bg: C.redSoft, icon: 'alert-circle' };
    case 'REFUSED':
      return { label: 'Recusado', color: C.redLight, bg: C.redSoft, icon: 'close-circle' };
    default:
      return { label: 'Criado', color: C.muted, bg: 'rgba(142,142,147,0.16)', icon: 'ellipse' };
  }
}

export function wdStatus(st?: WithdrawStatus): SInfo {
  switch (st) {
    case 'COMPLETED':
      return { label: 'Concluído', color: C.green, bg: C.greenSoft, icon: 'checkmark-circle' };
    case 'APPROVED':
      return { label: 'Enviando', color: C.blue, bg: 'rgba(10,132,255,0.15)', icon: 'paper-plane' };
    case 'PENDING_ANALYSIS':
      return { label: 'Em análise', color: C.orange, bg: C.orangeSoft, icon: 'time' };
    case 'REFUSED':
      return { label: 'Recusado', color: C.redLight, bg: C.redSoft, icon: 'close-circle' };
    case 'CANCELLED':
      return { label: 'Cancelado', color: C.muted, bg: 'rgba(142,142,147,0.16)', icon: 'close-circle' };
    default:
      return { label: '—', color: C.muted, bg: 'rgba(142,142,147,0.16)', icon: 'ellipse' };
  }
}

export function Row({
  icon,
  iconColor = C.text,
  iconBg = C.card3,
  title,
  subtitle,
  right,
  rightSub,
  onPress,
  last,
}: {
  icon: IconName;
  iconColor?: string;
  iconBg?: string;
  title: string;
  subtitle?: string;
  right?: React.ReactNode;
  rightSub?: React.ReactNode;
  onPress?: () => void;
  last?: boolean;
}) {
  return (
    <Pressable
      disabled={!onPress}
      onPress={() => {
        tap();
        onPress?.();
      }}
      style={({ pressed }) => [s.row, !last && s.rowBorder, { opacity: pressed ? 0.6 : 1 }]}
    >
      <View style={[s.rowIcon, { backgroundColor: iconBg }]}>
        <Ionicons name={icon} size={18} color={iconColor} />
      </View>
      <View style={{ flex: 1, marginRight: 10 }}>
        <Text style={s.rowTitle} numberOfLines={1}>
          {title}
        </Text>
        {subtitle ? (
          <Text style={s.rowSub} numberOfLines={1}>
            {subtitle}
          </Text>
        ) : null}
      </View>
      <View style={{ alignItems: 'flex-end', maxWidth: '45%' }}>
        {right}
        {rightSub}
      </View>
    </Pressable>
  );
}

export function Segmented<T extends string>({
  options,
  value,
  onChange,
}: {
  options: { value: T; label: string }[];
  value: T;
  onChange: (v: T) => void;
}) {
  return (
    <View style={s.seg}>
      {options.map((o) => {
        const on = o.value === value;
        return (
          <Pressable
            key={o.value}
            onPress={() => {
              tap();
              onChange(o.value);
            }}
            style={[s.segItem, on && s.segOn]}
          >
            <Text style={[s.segText, on && { color: C.text }]}>{o.label}</Text>
          </Pressable>
        );
      })}
    </View>
  );
}

export function Empty({ icon, title, text }: { icon: IconName; title: string; text?: string }) {
  return (
    <View style={{ alignItems: 'center', paddingVertical: 42, paddingHorizontal: 30 }}>
      <View style={[s.rowIcon, { width: 56, height: 56, borderRadius: 18, marginRight: 0, marginBottom: 14 }]}>
        <Ionicons name={icon} size={26} color={C.muted} />
      </View>
      <Text style={[s.rowTitle, { textAlign: 'center' }]}>{title}</Text>
      {text ? <Text style={[s.rowSub, { textAlign: 'center', marginTop: 4, lineHeight: 19 }]}>{text}</Text> : null}
    </View>
  );
}

export function ErrorBanner({ message }: { message: string | null }) {
  if (!message) return null;
  return (
    <View style={s.error}>
      <Ionicons name="warning" size={18} color={C.redLight} />
      <Text style={s.errorText}>{message}</Text>
    </View>
  );
}

/** Teclado numérico próprio (estilo app de banco): os dígitos entram pela direita */
export function Keypad({ value, onChange, max = 99_999_999 }: { value: number; onChange: (v: number) => void; max?: number }) {
  const press = (k: string) => {
    tap();
    if (k === 'del') return onChange(Math.floor(value / 10));
    const next = value * 10 + Number(k);
    if (next <= max) onChange(next);
  };
  const keys = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '', '0', 'del'];
  return (
    <View style={s.keypad}>
      {keys.map((k, i) =>
        k === '' ? (
          <View key={i} style={s.key} />
        ) : (
          <Pressable
            key={i}
            accessibilityLabel={k === 'del' ? 'Apagar' : k}
            onPress={() => press(k)}
            onLongPress={() => k === 'del' && onChange(0)}
            style={({ pressed }) => [s.key, { backgroundColor: pressed ? C.card3 : 'transparent' }]}
          >
            {k === 'del' ? (
              <Ionicons name="backspace-outline" size={26} color={C.text} />
            ) : (
              <Text style={s.keyText}>{k}</Text>
            )}
          </Pressable>
        )
      )}
    </View>
  );
}

export function Steps({ total, current }: { total: number; current: number }) {
  return (
    <View style={{ flexDirection: 'row', gap: 6, justifyContent: 'center' }}>
      {Array.from({ length: total }).map((_, i) => (
        <View
          key={i}
          style={{ height: 4, borderRadius: 2, width: i === current ? 26 : 8, backgroundColor: i <= current ? C.red : C.card3 }}
        />
      ))}
    </View>
  );
}

export function KV({ k, v, color, bold, last }: { k: string; v: string; color?: string; bold?: boolean; last?: boolean }) {
  return (
    <View style={[s.kv, !last && s.rowBorder]}>
      <Text style={s.kvK}>{k}</Text>
      <Text style={[s.kvV, color ? { color } : null, bold ? { fontWeight: '700', fontSize: 17 } : null]} numberOfLines={1}>
        {v}
      </Text>
    </View>
  );
}

export const s = StyleSheet.create({
  pill: {
    position: 'absolute',
    alignSelf: 'center',
    flexDirection: 'row',
    alignItems: 'center',
    gap: 7,
    paddingHorizontal: 14,
    paddingVertical: 8,
    borderRadius: 20,
    backgroundColor: '#1E1F25',
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,0.14)',
    shadowColor: '#000',
    shadowOpacity: 0.4,
    shadowRadius: 12,
    shadowOffset: { width: 0, height: 4 },
    elevation: 8,
  },
  pillText: { color: C.text, fontSize: 13.5, fontWeight: '700' },
  h1: { color: C.text, fontSize: 32, fontWeight: '800', letterSpacing: -0.6 },
  subtitle: { color: C.muted, fontSize: 15, marginTop: 4 },
  card: {
    backgroundColor: C.card,
    borderRadius: R.lg,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: C.border,
    padding: 18,
  },
  sectionRow: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginTop: 26, marginBottom: 10 },
  section: { color: C.text, fontSize: 20, fontWeight: '700', letterSpacing: -0.3 },
  sectionAction: { color: C.redLight, fontSize: 15, fontWeight: '600' },
  btn: { height: 56, borderRadius: 18, alignItems: 'center', justifyContent: 'center', paddingHorizontal: 20 },
  btnText: { fontSize: 17, fontWeight: '700' },
  iconBtn: {
    width: 40,
    height: 40,
    borderRadius: 20,
    backgroundColor: 'rgba(255,255,255,0.08)',
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: C.border,
  },
  badge: { paddingHorizontal: 9, paddingVertical: 3, borderRadius: 8, alignSelf: 'flex-start' },
  badgeText: { fontSize: 12, fontWeight: '700' },
  row: { flexDirection: 'row', alignItems: 'center', paddingVertical: 13 },
  rowBorder: { borderBottomWidth: StyleSheet.hairlineWidth, borderBottomColor: C.border },
  rowIcon: { width: 40, height: 40, borderRadius: 13, alignItems: 'center', justifyContent: 'center', marginRight: 12 },
  rowTitle: { color: C.text, fontSize: 16, fontWeight: '600' },
  rowSub: { color: C.muted, fontSize: 13, marginTop: 2 },
  seg: { flexDirection: 'row', backgroundColor: C.card2, borderRadius: 14, padding: 3 },
  segItem: { flex: 1, paddingVertical: 9, borderRadius: 11, alignItems: 'center' },
  segOn: { backgroundColor: C.card3 },
  segText: { color: C.muted, fontWeight: '600', fontSize: 14 },
  error: {
    flexDirection: 'row',
    gap: 10,
    alignItems: 'center',
    backgroundColor: C.redSoft,
    borderRadius: 14,
    padding: 14,
    marginVertical: 10,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: A(0.4),
  },
  errorText: { color: C.text, flex: 1, fontSize: 14, lineHeight: 19 },
  keypad: { flexDirection: 'row', flexWrap: 'wrap', marginTop: 8 },
  key: { width: '33.333%', height: 62, alignItems: 'center', justifyContent: 'center', borderRadius: 16 },
  keyText: { color: C.text, fontSize: 28, fontWeight: '500' },
  kv: { flexDirection: 'row', justifyContent: 'space-between', alignItems: 'center', paddingVertical: 13, gap: 12 },
  kvK: { color: C.muted, fontSize: 15 },
  kvV: { color: C.text, fontSize: 15, fontWeight: '600', flexShrink: 1, textAlign: 'right' },
});
