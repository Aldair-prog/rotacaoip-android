import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Pressable, RefreshControl, Share, StyleSheet, Text, TextInput, View } from 'react-native';
import { Ionicons } from '@expo/vector-icons';
import { ActionSheetIOS, Alert, Platform } from 'react-native';
import * as Print from 'expo-print';
import * as Sharing from 'expo-sharing';
import { useSession } from '../../src/session';
import { router } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Card, Empty, ErrorBanner, Glow, Header, IconButton, IconName, Money, Row, Segmented, tap, useRefresh, wdStatus } from '../../src/components/ui';
import { api, ApiError, Movement, MovementKind } from '../../src/lib/api';
import { brl, dayLabel, maskDoc, timeLabel } from '../../src/lib/format';
import { detectKeyType, displayKey } from '../../src/lib/pix';
import { C } from '../../src/theme';
import { BRAND, A } from '../../src/brand';

// Mesmo extrato do painel (Recebimentos): /statement/@me/movements
type Tab = 'all' | 'in' | 'out';
const KIND: Record<Tab, string | undefined> = { all: undefined, in: 'SALE', out: 'WITHDRAW' };

const LABEL: Record<MovementKind, string> = {
  SALE: 'Venda Pix',
  REFUND: 'Estorno',
  CHARGEBACK: 'Chargeback',
  PRE_CHARGEBACK: 'Pré-chargeback',
  RESERVE_RELEASE: 'Liberação de reserva',
  SPLIT: 'Split',
  AFFILIATE_COMMISSION: 'Comissão de afiliado',
  ADJUSTMENT: 'Ajuste',
  WITHDRAW: 'Pix enviado',
  ANTICIPATION: 'Antecipação',
  OTHER: 'Movimentação',
};

type Entry = { header: string } | { m: Movement };
type Period = 'all' | 'today' | '7d' | '30d';
const PERIODS: { value: Period; label: string }[] = [
  { value: 'all', label: 'Tudo' },
  { value: 'today', label: 'Hoje' },
  { value: '7d', label: '7 dias' },
  { value: '30d', label: '30 dias' },
];

function since(p: Period): number {
  if (p === 'all') return 0;
  const d = new Date();
  d.setHours(0, 0, 0, 0);
  if (p === '7d') d.setDate(d.getDate() - 6);
  if (p === '30d') d.setDate(d.getDate() - 29);
  return d.getTime();
}

function titleOf(m: Movement) {
  return m.counterparty_name || m.description || m.withdraw_pix_key || LABEL[m.kind] || 'Movimentação';
}

export default function Extrato() {
  const ins = useSafeAreaInsets();
  const [tab, setTab] = useState<Tab>('all');
  const [items, setItems] = useState<Movement[]>([]);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [more, setMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const reqId = useRef(0);
  const [period, setPeriod] = useState<Period>('all');
  const [query, setQuery] = useState('');
  const [searching, setSearching] = useState(false);

  const load = useCallback(
    async (reset: boolean, after?: string | null) => {
      const id = ++reqId.current;
      setError(null);
      try {
        const p = await api.movements({ perPage: 25, cursor: reset ? null : after, kind: KIND[tab] });
        if (id !== reqId.current) return;
        setItems((prev) => (reset ? p.items : [...prev, ...p.items]));
        setCursor(p.nextCursor);
      } catch (e) {
        if (id !== reqId.current) return;
        // Erro ao carregar "mais": só para a paginação, sem assustar com mensagem
        if (!reset) setCursor(null);
        else setError(e instanceof ApiError ? e.message : 'Não foi possível carregar o extrato.');
      }
    },
    [tab]
  );

  const r = useRefresh(() => load(true));

  useEffect(() => {
    setLoading(true);
    setCursor(null);
    load(true).finally(() => setLoading(false));
  }, [load]);

  const onEnd = async () => {
    if (!cursor || more || loading) return;
    setMore(true);
    await load(false, cursor);
    setMore(false);
  };

  // Filtro de período e busca (sobre o que já foi carregado; rolar carrega mais)
  const from = since(period);
  const q = query.trim().toLowerCase();
  const filtered = items.filter((m) => {
    if (from && new Date(m.occurred_at).getTime() < from) return false;
    if (!q) return true;
    return [titleOf(m), LABEL[m.kind], m.withdraw_pix_key, String((Math.abs(m.net_amount) / 100).toFixed(2)).replace('.', ',')]
      .filter(Boolean)
      .some((x) => String(x).toLowerCase().includes(q));
  });
  const totIn = filtered.filter((m) => m.net_amount > 0).reduce((a, m) => a + m.net_amount, 0);
  const totOut = filtered.filter((m) => m.net_amount < 0).reduce((a, m) => a - m.net_amount, 0);
  const filtering = period !== 'all' || !!q;

  const { company } = useSession();
  const periodLabel = PERIODS.find((p) => p.value === period)?.label ?? 'Tudo';

  const esc = (x: string) => x.replace(/[&<>"]/g, (c) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;' })[c] as string);

  /** Gera o extrato em PDF (com a identidade da marca) e abre o compartilhar do celular */
  const sharePdf = async () => {
    const rows = filtered
      .map(
        (m) => `<tr>
          <td>${esc(dayLabel(m.occurred_at))} ${esc(timeLabel(m.occurred_at))}</td>
          <td>${esc(titleOf(m))}<div class="k">${esc(LABEL[m.kind] ?? 'Movimentação')}</div></td>
          <td class="v ${m.net_amount < 0 ? 'out' : 'in'}">${m.net_amount < 0 ? '-' : '+'}${esc(brl(Math.abs(m.net_amount)))}</td>
        </tr>`
      )
      .join('');
    const html = `<html><head><meta charset="utf-8"/><style>
      body{font-family:-apple-system,Helvetica;color:#111;padding:28px}
      .top{display:flex;justify-content:space-between;align-items:flex-end;border-bottom:3px solid ${C.red};padding-bottom:12px}
      h1{margin:0;font-size:24px} .s{color:#666;font-size:12px;margin-top:4px}
      .tot{display:flex;gap:24px;margin:18px 0} .tot div{background:#f5f5f7;border-radius:10px;padding:10px 14px;font-size:12px;color:#666}
      .tot b{display:block;font-size:17px;color:#111;margin-top:2px}
      table{width:100%;border-collapse:collapse;font-size:12px} td{padding:9px 4px;border-bottom:1px solid #eee;vertical-align:top}
      .k{color:#888;font-size:10.5px;margin-top:2px} .v{text-align:right;font-weight:700;white-space:nowrap}
      .in{color:#1a9b45} .out{color:#111} .f{margin-top:20px;color:#999;font-size:10px;text-align:center}
    </style></head><body>
      <div class="top"><div><h1>Extrato ${esc(BRAND.name)}</h1><div class="s">${esc(company?.name ?? '')}${company?.document?.number ? ' · ' + company.document.type + ' ' + esc(maskDoc(company.document.number)) : ''}</div></div>
      <div class="s">Período: ${esc(periodLabel)}<br/>Gerado em ${esc(new Date().toLocaleString('pt-BR'))}</div></div>
      <div class="tot"><div>Entradas<b class="in">+${esc(brl(totIn))}</b></div><div>Saídas<b>-${esc(brl(totOut))}</b></div><div>Movimentações<b>${filtered.length}</b></div></div>
      <table>${rows || '<tr><td>Nenhuma movimentação no período.</td></tr>'}</table>
      <div class="f">Documento gerado pelo app ${esc(BRAND.name)}</div>
    </body></html>`;
    try {
      const { uri } = await Print.printToFileAsync({ html });
      await Sharing.shareAsync(uri, { mimeType: 'application/pdf', UTI: 'com.adobe.pdf', dialogTitle: `Extrato ${BRAND.name}` });
    } catch {
      Alert.alert('Não foi possível gerar o PDF', 'Tente de novo em instantes.');
    }
  };

  const shareMenu = () => {
    tap();
    if (Platform.OS === 'ios') {
      ActionSheetIOS.showActionSheetWithOptions(
        { options: ['Exportar em PDF', 'Compartilhar como texto', 'Cancelar'], cancelButtonIndex: 2, title: `Extrato · ${periodLabel}` },
        (i) => {
          if (i === 0) sharePdf();
          if (i === 1) shareStatement();
        }
      );
      return;
    }
    // Android: mesmo menu, como caixa de opções
    Alert.alert(`Extrato · ${periodLabel}`, undefined, [
      { text: 'Exportar em PDF', onPress: sharePdf },
      { text: 'Como texto', onPress: shareStatement },
      { text: 'Cancelar', style: 'cancel' },
    ]);
  };

  const shareStatement = () => {
    const lines = filtered.map(
      (m) =>
        `${dayLabel(m.occurred_at)} ${timeLabel(m.occurred_at)}  ${m.net_amount < 0 ? '-' : '+'}${brl(Math.abs(m.net_amount))}  ${titleOf(m)}`
    );
    Share.share({
      message: `Extrato ${BRAND.name}\nEntradas: ${brl(totIn)} · Saídas: ${brl(totOut)}\n\n${lines.join('\n')}`,
    });
  };

  // Agrupa por dia
  const data: Entry[] = [];
  let last = '';
  filtered.forEach((m) => {
    const d = dayLabel(m.occurred_at);
    if (d !== last) {
      data.push({ header: d });
      last = d;
    }
    data.push({ m });
  });

  return (
    <View style={{ flex: 1, backgroundColor: C.bg }}>
      <Glow />
      <FlatList
        data={data}
        keyExtractor={(e, i) => ('header' in e ? `h-${e.header}-${i}` : e.m.id)}
        style={{ marginTop: ins.top }}
        contentContainerStyle={{ paddingTop: 10, paddingBottom: ins.bottom + 120, paddingHorizontal: 20 }}
        showsVerticalScrollIndicator={false}
        refreshControl={<RefreshControl tintColor={C.red} refreshing={r.refreshing} onRefresh={r.run} />}
        onEndReached={onEnd}
        onEndReachedThreshold={0.4}
        ListHeaderComponent={
          <View>
            <Header
              title="Extrato"
              subtitle="Tudo que entrou e saiu da sua conta"
              right={
                <View style={{ flexDirection: 'row', gap: 10 }}>
                  <IconButton icon="search" label="Buscar" onPress={() => setSearching((v) => !v)} />
                  <IconButton icon="share-outline" label="Exportar extrato" onPress={shareMenu} />
                </View>
              }
            />
            {searching ? (
              <View style={xs.search}>
                <Ionicons name="search" size={18} color={C.muted} />
                <TextInput
                  value={query}
                  onChangeText={setQuery}
                  placeholder="Nome, chave ou valor"
                  placeholderTextColor={C.dim}
                  autoFocus
                  autoCorrect={false}
                  autoCapitalize="none"
                  style={xs.searchInput}
                  returnKeyType="search"
                />
                {query ? (
                  <Pressable hitSlop={10} onPress={() => setQuery('')}>
                    <Ionicons name="close-circle" size={18} color={C.dim} />
                  </Pressable>
                ) : null}
              </View>
            ) : null}
            <Segmented
              value={tab}
              onChange={(v) => setTab(v)}
              options={[
                { value: 'all', label: 'Tudo' },
                { value: 'in', label: 'Entradas' },
                { value: 'out', label: 'Saídas' },
              ]}
            />
            <View style={xs.periods}>
              {PERIODS.map((p) => (
                <Pressable
                  key={p.value}
                  onPress={() => {
                    tap();
                    setPeriod(p.value);
                  }}
                  style={[xs.period, period === p.value && xs.periodOn]}
                >
                  <Text style={[xs.periodText, period === p.value && { color: C.text }]}>{p.label}</Text>
                </Pressable>
              ))}
            </View>
            {filtering && !loading ? (
              <View style={xs.totals}>
                <View style={{ flex: 1 }}>
                  <Text style={xs.totLabel}>Entradas</Text>
                  <Text style={[xs.totValue, { color: C.green }]}>+{brl(totIn)}</Text>
                </View>
                <View style={{ flex: 1, alignItems: 'flex-end' }}>
                  <Text style={xs.totLabel}>Saídas</Text>
                  <Text style={xs.totValue}>-{brl(totOut)}</Text>
                </View>
              </View>
            ) : null}
            <ErrorBanner message={error} />
            {loading ? <ActivityIndicator color={C.red} style={{ marginTop: 40 }} /> : null}
          </View>
        }
        ListEmptyComponent={
          loading ? null : (
            <Card style={{ marginTop: 16 }}>
              <Empty icon="swap-vertical" title="Nada por aqui ainda" text="Quando houver movimentações, elas aparecem aqui." />
            </Card>
          )
        }
        ListFooterComponent={more ? <ActivityIndicator color={C.red} style={{ marginVertical: 20 }} /> : null}
        renderItem={({ item }) => {
          if ('header' in item)
            return (
              <Text style={{ color: C.muted, fontSize: 13, fontWeight: '700', marginTop: 22, marginBottom: 4, textTransform: 'uppercase', letterSpacing: 0.6 }}>
                {item.header}
              </Text>
            );
          return <MovementRow m={item.m} />;
        }}
      />
    </View>
  );
}

function MovementRow({ m }: { m: Movement }) {
  const isOut = m.kind === 'WITHDRAW' || m.net_amount < 0;
  const value = Math.abs(m.net_amount ?? m.gross_amount ?? 0);
  const time = timeLabel(m.occurred_at);

  if (m.kind === 'WITHDRAW') {
    const info = wdStatus(m.withdraw_status ?? undefined);
    const failed = m.withdraw_status === 'REFUSED' || m.withdraw_status === 'CANCELLED';
    const key = m.withdraw_pix_key;
    const title =
      m.withdraw_destination_type === 'QR_CODE'
        ? m.counterparty_name || m.description || 'Pagamento QR Code'
        : m.counterparty_name || (key ? displayKey(key, detectKeyType(key)) : 'Pix enviado');
    return (
      <Row
        icon={failed ? info.icon : 'arrow-up'}
        iconColor={failed ? info.color : C.redLight}
        iconBg={failed ? info.bg : C.redSoft}
        title={title}
        subtitle={`${m.withdraw_status ? info.label : 'Saque'} · ${time}`}
        right={
          <Money
            cents={value}
            sign="-"
            style={{ fontSize: 16, fontWeight: '700', color: failed ? C.dim : C.text, textDecorationLine: failed ? 'line-through' : 'none' }}
          />
        }
        onPress={m.withdraw_id ? () => router.push(`/saque/${m.withdraw_id}`) : undefined}
      />
    );
  }

  const icon: IconName =
    m.kind === 'SALE'
      ? 'arrow-down'
      : m.kind === 'REFUND' || m.kind === 'CHARGEBACK' || m.kind === 'PRE_CHARGEBACK'
        ? 'arrow-undo'
        : m.kind === 'ANTICIPATION'
          ? 'flash'
          : 'swap-horizontal';
  const color = isOut ? C.redLight : C.green;
  const bg = isOut ? C.redSoft : C.greenSoft;
  const fee = m.fee_amount ? ` · taxa ${Math.abs(m.fee_amount) / 100 > 0 ? 'R$ ' + (Math.abs(m.fee_amount) / 100).toFixed(2).replace('.', ',') : ''}` : '';
  return (
    <Row
      icon={icon}
      iconColor={color}
      iconBg={bg}
      title={m.counterparty_name || m.description || LABEL[m.kind] || 'Movimentação'}
      subtitle={`${LABEL[m.kind] ?? 'Movimentação'} · ${time}${fee}`}
      right={<Money cents={value} sign={isOut ? '-' : '+'} style={{ fontSize: 16, fontWeight: '700', color: isOut ? C.text : C.green }} />}
      onPress={m.transaction_id ? () => router.push(`/cobranca/${m.transaction_id}`) : undefined}
    />
  );
}

const xs = StyleSheet.create({
  search: {
    flexDirection: 'row',
    alignItems: 'center',
    gap: 10,
    backgroundColor: '#15161A',
    borderRadius: 14,
    paddingHorizontal: 14,
    height: 46,
    marginBottom: 12,
  },
  searchInput: { flex: 1, color: C.text, fontSize: 16 },
  periods: { flexDirection: 'row', gap: 8, marginTop: 12 },
  period: { paddingHorizontal: 14, height: 34, borderRadius: 17, backgroundColor: '#15161A', justifyContent: 'center' },
  periodOn: { backgroundColor: A(0.18), borderWidth: 1, borderColor: A(0.5) },
  periodText: { color: C.muted, fontSize: 13.5, fontWeight: '600' },
  totals: {
    flexDirection: 'row',
    marginTop: 12,
    backgroundColor: '#111114',
    borderRadius: 16,
    padding: 14,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,0.08)',
  },
  totLabel: { color: C.muted, fontSize: 12 },
  totValue: { color: C.text, fontSize: 17, fontWeight: '800', marginTop: 2, fontVariant: ['tabular-nums'] },
});
