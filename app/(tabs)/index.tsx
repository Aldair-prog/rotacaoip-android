import React, { useCallback, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { router, useFocusEffect } from 'expo-router';
import { Image } from 'expo-image';
import { LinearGradient } from 'expo-linear-gradient';
import { Ionicons } from '@expo/vector-icons';
import {
  Badge,
  Card,
  Empty,
  ErrorBanner,
  IconButton,
  IconName,
  Money,
  Row,
  Screen,
  SectionTitle,
  tap,
  txStatus,
  wdStatus,
} from '../../src/components/ui';
import { api, ApiError, Balance, DaySummary, Transaction, Withdraw } from '../../src/lib/api';
import { brl, dayLabel, greeting, timeLabel, txDate } from '../../src/lib/format';
import { displayKey } from '../../src/lib/pix';
import { useSession } from '../../src/session';
import { C } from '../../src/theme';
import { BRAND, A, LOGO } from '../../src/brand';
import { BrandLogo } from '../../src/components/Intro';

type Item = { kind: 'in'; t: Transaction; date: string } | { kind: 'out'; w: Withdraw; date: string };

export default function Home() {
  const { company, hideValues, setHideValues, refreshCompany, isAdmin } = useSession();
  const [balance, setBalance] = useState<Balance | null>(null);
  const [items, setItems] = useState<Item[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [today, setToday] = useState<DaySummary | null>(null);
  const [unread, setUnread] = useState(0);

  const load = useCallback(async () => {
    setError(null);
    const [b, tx, wd] = await Promise.allSettled([
      api.balance(),
      api.transactions({ perPage: 20 }),
      api.withdraws({ perPage: 8 }),
    ]);
    // Resumo do dia e avisos não lidos (não bloqueiam a tela se falharem)
    api.today().then(setToday).catch(() => {});
    Promise.all([api.notifications(20).catch(() => []), api.announcements().catch(() => [])]).then(([a, b2]) =>
      setUnread([...a, ...b2].filter((n) => !n.read_at).length)
    );
    if (b.status === 'fulfilled') setBalance(b.value);
    const list: Item[] = [];
    // Cobranças recusadas/canceladas são só tentativas: não poluem a tela inicial (seguem no Extrato)
    if (tx.status === 'fulfilled')
      tx.value.items
        .filter((t) => !['REFUSED', 'CANCELLED', 'EXPIRED'].includes(String(t.status)))
        .forEach((t) => list.push({ kind: 'in', t, date: txDate(t) }));
    if (wd.status === 'fulfilled') wd.value.items.forEach((w) => list.push({ kind: 'out', w, date: txDate(w) }));
    list.sort((a, b2) => (a.date < b2.date ? 1 : -1));
    setItems(list.slice(0, 8));
    const firstErr = [b, tx, wd].find((r) => r.status === 'rejected') as PromiseRejectedResult | undefined;
    if (firstErr) setError(firstErr.reason instanceof ApiError ? firstErr.reason.message : 'Não foi possível atualizar.');
  }, []);

  useFocusEffect(
    useCallback(() => {
      load();
    }, [load])
  );

  const onRefresh = () => Promise.all([load(), refreshCompany()]);

  const names = [company?.trade_name, company?.name].filter(Boolean) as string[];
  const name = names.sort((a, b) => a.length - b.length)[0] ?? '';

  return (
    <Screen onRefresh={onRefresh}>
      {/* Topo */}
      <View style={st.top}>
        <Pressable
          disabled={!isAdmin}
          onPress={() => {
            tap();
            router.push('/admin');
          }}
          hitSlop={10}
          accessibilityLabel={isAdmin ? 'Abrir Painel Admin' : undefined}
        >
          <View style={{ margin: -12 }}>
            <BrandLogo size={40} rings={false} />
          </View>
        </Pressable>
        <View style={{ flex: 1, marginLeft: 12 }}>
          <Text style={st.hello}>{greeting()},</Text>
          <Text style={st.name} numberOfLines={1}>
            {name || BRAND.name}
          </Text>
        </View>
        <IconButton
          icon={hideValues ? 'eye-off-outline' : 'eye-outline'}
          label={hideValues ? 'Mostrar valores' : 'Ocultar valores'}
          onPress={() => setHideValues(!hideValues)}
        />
        <View style={{ width: 10 }} />
        <View>
          <IconButton icon="notifications-outline" label="Avisos" onPress={() => router.push('/avisos')} />
          {unread > 0 ? (
            <View style={st.dot} pointerEvents="none">
              <Text style={st.dotText}>{unread > 9 ? '9+' : unread}</Text>
            </View>
          ) : null}
        </View>
      </View>

      {/* Saldo */}
      <LinearGradient colors={BRAND.cardGradient} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={st.balance}>
        <LinearGradient
          pointerEvents="none"
          colors={[A(0.35), A(0)]}
          start={{ x: 1, y: 0 }}
          end={{ x: 0.3, y: 0.8 }}
          style={st.balanceGlow}
        />
        <View style={{ flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between' }}>
          <Text style={st.balanceLabel}>Saldo disponível</Text>
          <Badge label={`CONTA ${BRAND.short}`} color={C.silver} bg="rgba(255,255,255,0.08)" />
        </View>
        <Money cents={balance?.available_balance} style={st.balanceValue} />
        <View style={st.balanceRow}>
          <View style={{ flex: 1 }}>
            <Text style={st.miniLabel}>Saldo total</Text>
            <Money cents={balance?.total_balance} style={st.miniValue} />
          </View>
          <View style={st.vDivider} />
          <View style={{ flex: 1, paddingLeft: 16 }}>
            <Text style={st.miniLabel}>Reservado em saques</Text>
            <Money cents={balance?.reserved_amount} style={st.miniValue} />
          </View>
        </View>
      </LinearGradient>

      <ErrorBanner message={error} />

      {/* Ações rápidas */}
      <View style={st.actions}>
        <Action icon="qr-code" label="Receber" onPress={() => router.navigate('/receber')} />
        <Action icon="paper-plane" label="Enviar Pix" onPress={() => router.navigate('/enviar')} />
        <Action icon="scan" label="Pagar QR" onPress={() => router.push('/pagar-qr')} />
        <Action icon="receipt" label="Extrato" onPress={() => router.navigate('/extrato')} />
      </View>

      {/* Hoje */}
      <SectionTitle title="Hoje" />
      <Card style={st.todayCard}>
        <TodayStat icon="arrow-down" color={C.green} bg={C.greenSoft} label="Entradas" value={today ? (hideValues ? '••••' : brl(today.incoming)) : '—'} />
        <View style={st.todayDiv} />
        <TodayStat icon="arrow-up" color={C.redLight} bg={C.redSoft} label="Saídas" value={today ? (hideValues ? '••••' : brl(today.outgoing)) : '—'} />
        <View style={st.todayDiv} />
        <TodayStat icon="flash" color={C.silver} bg="rgba(255,255,255,0.07)" label="Pix recebidos" value={today ? String(today.sales) : '—'} />
      </Card>

      {/* Movimentações */}
      <SectionTitle title="Últimas movimentações" action="Ver tudo" onAction={() => router.navigate('/extrato')} />
      <Card style={{ paddingVertical: 4 }}>
        {items === null ? (
          <View style={{ paddingVertical: 30, alignItems: 'center' }}>
            <Text style={{ color: C.muted }}>Carregando…</Text>
          </View>
        ) : items.length === 0 ? (
          <Empty icon="swap-vertical" title="Nenhuma movimentação ainda" text="Gere uma cobrança Pix pra começar a receber." />
        ) : (
          items.map((it, i) => <MovementRow key={it.kind + (it.kind === 'in' ? it.t.id : it.w.id)} item={it} last={i === items.length - 1} />)
        )}
      </Card>
    </Screen>
  );
}

function TodayStat({ icon, color, bg, label, value }: { icon: IconName; color: string; bg: string; label: string; value: string }) {
  return (
    <View style={{ flex: 1, alignItems: 'center' }}>
      <View style={[st.todayIcon, { backgroundColor: bg }]}>
        <Ionicons name={icon} size={16} color={color} />
      </View>
      <Text style={st.todayValue} numberOfLines={1} adjustsFontSizeToFit>
        {value}
      </Text>
      <Text style={st.todayLabel}>{label}</Text>
    </View>
  );
}

function Action({ icon, label, onPress }: { icon: IconName; label: string; onPress: () => void }) {
  return (
    <Pressable
      onPress={() => {
        tap();
        onPress();
      }}
      style={({ pressed }) => [st.action, { opacity: pressed ? 0.7 : 1, transform: [{ scale: pressed ? 0.96 : 1 }] }]}
    >
      <View style={st.actionIcon}>
        <Ionicons name={icon} size={23} color={C.redLight} />
      </View>
      <Text style={st.actionLabel}>{label}</Text>
    </Pressable>
  );
}

function MovementRow({ item, last }: { item: Item; last?: boolean }) {
  if (item.kind === 'in') {
    const t = item.t;
    const info = txStatus(t.status);
    const paid = t.status === 'APPROVED';
    const dead = ['REFUSED', 'CANCELLED', 'CHARGEBACK', 'REFUNDED'].includes(t.status ?? '');
    return (
      <Row
        icon={paid ? 'arrow-down' : info.icon}
        iconColor={paid ? C.green : info.color}
        iconBg={paid ? C.greenSoft : info.bg}
        title={t.customer?.name || t.payer?.name || 'Cobrança Pix'}
        subtitle={`${info.label} · ${dayLabel(item.date)}, ${timeLabel(item.date)}`}
        right={
          <Money
            cents={t.amount}
            sign={paid ? '+' : undefined}
            style={{ fontSize: 16, fontWeight: '700', color: paid ? C.green : dead ? C.dim : C.sub, textDecorationLine: dead ? 'line-through' : 'none' }}
          />
        }
        onPress={() => router.push(`/cobranca/${t.id}`)}
        last={last}
      />
    );
  }
  const w = item.w;
  const info = wdStatus(w.status);
  const failed = w.status === 'REFUSED' || w.status === 'CANCELLED';
  return (
    <Row
      icon={failed ? info.icon : 'arrow-up'}
      iconColor={failed ? info.color : C.redLight}
      iconBg={failed ? info.bg : C.redSoft}
      title={w.destination_type === 'QR_CODE' ? w.description || 'Pagamento QR Code' : displayKey(w.pix_key ?? 'Pix enviado', w.pix_key_type ?? null)}
      subtitle={`${info.label} · ${dayLabel(item.date)}, ${timeLabel(item.date)}`}
      right={
        <Money
          cents={w.amount}
          sign="-"
          style={{ fontSize: 16, fontWeight: '700', color: failed ? C.muted : C.text, textDecorationLine: failed ? 'line-through' : 'none' }}
        />
      }
      onPress={() => router.push(`/saque/${w.id}`)}
      last={last}
    />
  );
}

const st = StyleSheet.create({
  top: { flexDirection: 'row', alignItems: 'center', marginBottom: 20, marginTop: 4 },
  hello: { color: C.muted, fontSize: 14 },
  name: { color: C.text, fontSize: 19, fontWeight: '700', letterSpacing: -0.2 },
  balance: { borderRadius: 26, padding: 20, overflow: 'hidden', borderWidth: StyleSheet.hairlineWidth, borderColor: A(0.35) },
  balanceGlow: { position: 'absolute', top: 0, right: 0, width: '75%', height: '100%' },
  balanceLabel: { color: C.sub, fontSize: 15, fontWeight: '600' },
  balanceValue: { fontSize: 38, fontWeight: '800', marginTop: 10, letterSpacing: -1 },
  balanceRow: { flexDirection: 'row', marginTop: 18, paddingTop: 16, borderTopWidth: StyleSheet.hairlineWidth, borderTopColor: 'rgba(255,255,255,0.1)' },
  vDivider: { width: StyleSheet.hairlineWidth, backgroundColor: 'rgba(255,255,255,0.12)' },
  miniLabel: { color: C.muted, fontSize: 12, marginBottom: 4 },
  miniValue: { fontSize: 16, fontWeight: '700' },
  actions: { flexDirection: 'row', justifyContent: 'space-between', marginTop: 18 },
  action: { width: '23%', alignItems: 'center' },
  actionIcon: {
    width: 62,
    height: 62,
    borderRadius: 21,
    backgroundColor: A(0.09),
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 1,
    borderColor: A(0.25),
  },
  dot: {
    position: 'absolute',
    top: -3,
    right: -3,
    minWidth: 18,
    height: 18,
    paddingHorizontal: 4,
    borderRadius: 9,
    backgroundColor: C.red,
    alignItems: 'center',
    justifyContent: 'center',
    borderWidth: 2,
    borderColor: C.bg,
  },
  dotText: { color: '#fff', fontSize: 10, fontWeight: '800' },
  todayCard: { flexDirection: 'row', paddingVertical: 16, paddingHorizontal: 8 },
  todayDiv: { width: StyleSheet.hairlineWidth, backgroundColor: 'rgba(255,255,255,0.1)', marginVertical: 4 },
  todayIcon: { width: 30, height: 30, borderRadius: 15, alignItems: 'center', justifyContent: 'center' },
  todayValue: { color: C.text, fontSize: 16, fontWeight: '800', marginTop: 8, fontVariant: ['tabular-nums'], paddingHorizontal: 4 },
  todayLabel: { color: C.muted, fontSize: 12, marginTop: 2 },
  actionLabel: { color: C.sub, fontSize: 13, fontWeight: '600', marginTop: 8 },
});
