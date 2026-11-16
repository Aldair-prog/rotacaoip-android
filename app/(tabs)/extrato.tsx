import React, { useCallback, useEffect, useRef, useState } from 'react';
import { ActivityIndicator, FlatList, Modal, Pressable, RefreshControl, Share, StyleSheet, Text, TextInput, View } from 'react-native';
import { LinearGradient } from 'expo-linear-gradient';
import { Image } from 'expo-image';
import { Ionicons } from '@expo/vector-icons';
import { Alert } from 'react-native';
import * as Print from 'expo-print';
import * as Sharing from 'expo-sharing';
import { useSession } from '../../src/session';
import { router } from 'expo-router';
import { useSafeAreaInsets } from 'react-native-safe-area-context';
import { Card, Empty, ErrorBanner, Glow, Header, IconButton, IconName, Money, Row, Segmented, tap, useRefresh, wdStatus, RefreshPill } from '../../src/components/ui';
import { api, ApiError, Movement, MovementKind } from '../../src/lib/api';
import { brl, dayLabel, maskDoc, timeLabel } from '../../src/lib/format';
import { detectKeyType, displayKey } from '../../src/lib/pix';
import { C } from '../../src/theme';
import { BRAND, A, LOGO, BRAND_ID } from '../../src/brand';
import { CONNECT_LOGO_B64, STRATTON_LOGO_B64 } from '../../src/logoData';
import { screenCache } from '../../src/lib/cache';

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
  const [items, setItems] = useState<Movement[]>(() => (screenCache.get('extrato.all') as Movement[]) ?? []);
  const [cursor, setCursor] = useState<string | null>(null);
  const [loading, setLoading] = useState(true);
  const [more, setMore] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const reqId = useRef(0);
  const [period, setPeriod] = useState<Period>('all');
  const [query, setQuery] = useState('');
  const [searching, setSearching] = useState(false);

  // Carrega o extrato inteiro do período escolhido (páginas de 100, em segundo plano).
  // Assim os totais, o texto e o PDF batem com o painel, e não só com o que apareceu na tela.
  const [complete, setComplete] = useState(false);
  const [syncing, setSyncing] = useState(false);
  const MAX_PAGES = 200;

  const loadAll = useCallback(async () => {
    const id = ++reqId.current;
    setError(null);
    setSyncing(true);
    setComplete(false);
    const limit = since(period);
    let after: string | null = null;
    let acc: Movement[] = [];
    try {
      for (let i = 0; i < MAX_PAGES; i++) {
        const p = await api.movements({ perPage: 100, cursor: after, kind: KIND[tab] });
        if (id !== reqId.current) return;
        acc = acc.concat(p.items);
        setItems(acc);
        if (i === 0) {
          setLoading(false);
          screenCache.set(`extrato.${tab}`, p.items);
        }
        after = p.nextCursor;
        const oldest = p.items[p.items.length - 1];
        if (!after || (limit && oldest && new Date(oldest.occurred_at).getTime() < limit)) {
          setComplete(true);
          after = null;
          break;
        }
        // "Tudo": as 1.000 mais recentes de uma vez; o resto carrega ao rolar
        if (!limit && i >= 9) break;
      }
      setCursor(after);
    } catch (e) {
      if (id !== reqId.current) return;
      if (!acc.length) setError(e instanceof ApiError ? e.message : 'Não foi possível carregar o extrato.');
      setCursor(null);
    } finally {
      if (id === reqId.current) {
        setSyncing(false);
        setLoading(false);
      }
    }
  }, [tab, period]);

  const loadMore = useCallback(
    async (after: string) => {
      const id = reqId.current;
      try {
        const p = await api.movements({ perPage: 100, cursor: after, kind: KIND[tab] });
        if (id !== reqId.current) return;
        setItems((prev) => [...prev, ...p.items]);
        setCursor(p.nextCursor);
        if (!p.nextCursor) setComplete(true);
      } catch {
        if (id === reqId.current) setCursor(null);
      }
    },
    [tab]
  );

  const r = useRefresh(loadAll);

  useEffect(() => {
    const cached = screenCache.get(`extrato.${tab}`) as Movement[] | undefined;
    setLoading(!cached);
    setItems(cached ?? []);
    setCursor(null);
    loadAll();
  }, [loadAll]);

  const onEnd = async () => {
    if (!cursor || more || loading || syncing) return;
    setMore(true);
    await loadMore(cursor);
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
  const [sheet, setSheet] = useState(false);
  const [busy, setBusy] = useState<'pdf' | 'txt' | null>(null);
  const tabLabel = tab === 'in' ? 'Entradas' : tab === 'out' ? 'Saídas' : 'Tudo';
  const scope = `${periodLabel}${tab !== 'all' ? ' · ' + tabLabel : ''}`;
  const partial = !complete;
  const companyLine = `${company?.name ?? ''}${company?.document?.number ? ' · ' + company.document.type + ' ' + maskDoc(company.document.number) : ''}`;
  const failedWd = (m: Movement) => m.kind === 'WITHDRAW' && (m.withdraw_status === 'REFUSED' || m.withdraw_status === 'CANCELLED');
  const nameOf = (m: Movement) =>
    m.kind === 'WITHDRAW'
      ? m.withdraw_destination_type === 'QR_CODE'
        ? m.counterparty_name || m.description || 'Pagamento QR Code'
        : m.counterparty_name || (m.withdraw_pix_key ? displayKey(m.withdraw_pix_key, detectKeyType(m.withdraw_pix_key)) : 'Pix enviado')
      : titleOf(m);
  const typeOf = (m: Movement) => (m.kind === 'WITHDRAW' && m.withdraw_status ? `${LABEL.WITHDRAW} · ${wdStatus(m.withdraw_status).label}` : LABEL[m.kind] ?? 'Movimentação');

  /** Agrupa por dia para o texto e o PDF */
  const byDay = () => {
    const g: { day: string; list: Movement[]; inn: number; out: number }[] = [];
    filtered.forEach((m) => {
      const d = dayLabel(m.occurred_at);
      let cur = g[g.length - 1];
      if (!cur || cur.day !== d) g.push((cur = { day: d, list: [], inn: 0, out: 0 }));
      cur.list.push(m);
      if (m.net_amount > 0) cur.inn += m.net_amount;
      else cur.out -= m.net_amount;
    });
    return g;
  };

  /** Extrato em PDF com a identidade da marca */
  const sharePdf = async () => {
    setBusy('pdf');
    const logo = BRAND_ID === 'stratton' ? STRATTON_LOGO_B64 : CONNECT_LOGO_B64;
    const days = byDay()
      .map(
        (g) => `<div class="day"><span>${esc(g.day)}</span><span class="dt">${g.inn ? `<b class="in">+${esc(brl(g.inn))}</b>` : ''}${g.out ? `<b class="out">-${esc(brl(g.out))}</b>` : ''}</span></div>
        <table>${g.list
          .map((m) => {
            const out = m.net_amount < 0 || m.kind === 'WITHDRAW';
            const fail = failedWd(m);
            const fee = m.fee_amount ? ` · taxa ${esc(brl(Math.abs(m.fee_amount)))}` : '';
            return `<tr class="${fail ? 'fail' : ''}">
              <td class="ic"><div class="dot ${out ? 'o' : 'i'}">${out ? '&#8593;' : '&#8595;'}</div></td>
              <td><div class="n">${esc(nameOf(m))}</div><div class="k">${esc(typeOf(m))} · ${esc(timeLabel(m.occurred_at))}${fee}</div></td>
              <td class="v ${out ? 'out' : 'in'}">${out ? '-' : '+'}${esc(brl(Math.abs(m.net_amount)))}</td></tr>`;
          })
          .join('')}</table>`
      )
      .join('');
    const res = totIn - totOut;
    const html = `<html><head><meta charset="utf-8"/><style>
      @page{margin:0} *{box-sizing:border-box}
      body{font-family:-apple-system,Roboto,Helvetica,Arial;color:#15151a;margin:0;background:#fff}
      .hero{background:linear-gradient(135deg,${BRAND.accentDeep},${BRAND.accent} 60%,${BRAND.accentLight});color:#fff;padding:30px 34px 64px;position:relative;overflow:hidden}
      .hero:after{content:'';position:absolute;right:-60px;top:-60px;width:240px;height:240px;border-radius:50%;background:rgba(255,255,255,.08)}
      .hrow{display:flex;align-items:center;gap:16px;position:relative;z-index:1}
      .logo{width:62px;height:62px;border-radius:18px;background:rgba(0,0,0,.25);display:flex;align-items:center;justify-content:center;border:1px solid rgba(255,255,255,.25)}
      .logo img{width:46px;height:46px;object-fit:contain}
      h1{margin:0;font-size:27px;letter-spacing:.3px} .sub{opacity:.85;font-size:12.5px;margin-top:3px}
      .meta{margin-left:auto;text-align:right;font-size:11.5px;opacity:.9;line-height:1.6}
      .pill{display:inline-block;background:rgba(255,255,255,.18);border:1px solid rgba(255,255,255,.3);padding:3px 10px;border-radius:20px;font-weight:700}
      .cards{display:flex;gap:12px;margin:-40px 34px 8px;position:relative;z-index:2}
      .card{flex:1;background:#fff;border-radius:16px;padding:14px 16px;box-shadow:0 6px 22px rgba(0,0,0,.10);border:1px solid #eee}
      .card small{color:#8a8a93;font-size:10.5px;text-transform:uppercase;letter-spacing:.8px;font-weight:700}
      .card b{display:block;font-size:17px;margin-top:5px;white-space:nowrap}
      .wrap{padding:10px 34px 30px}
      .warn{background:#fff7e6;border:1px solid #ffe2a8;color:#8a5a00;border-radius:12px;padding:9px 12px;font-size:11px;margin:10px 0}
      .day{display:flex;justify-content:space-between;align-items:center;margin:20px 0 6px;padding-bottom:6px;border-bottom:2px solid ${BRAND.accent}22;font-size:12px;font-weight:800;text-transform:uppercase;letter-spacing:.8px;color:${BRAND.accentDeep}}
      .dt b{margin-left:12px;font-size:11.5px;letter-spacing:0;text-transform:none}
      table{width:100%;border-collapse:collapse;font-size:12px} tr{page-break-inside:avoid}
      td{padding:8px 4px;border-bottom:1px solid #f0f0f3;vertical-align:middle}
      .ic{width:34px} .dot{width:26px;height:26px;border-radius:9px;display:flex;align-items:center;justify-content:center;font-weight:900;font-size:14px}
      .dot.i{background:#e7f8ee;color:#1a9b45} .dot.o{background:${BRAND.accent}18;color:${BRAND.accentDeep}}
      .n{font-weight:700;word-break:break-all} .k{color:#8a8a93;font-size:10.5px;margin-top:2px}
      .v{text-align:right;font-weight:800;white-space:nowrap;font-size:12.5px}
      .in{color:#1a9b45} .out{color:#15151a} .fail td{opacity:.45} .fail .v{text-decoration:line-through}
      .foot{margin-top:26px;padding-top:12px;border-top:1px solid #eee;display:flex;justify-content:space-between;color:#9a9aa3;font-size:9.5px}
    </style></head><body>
      <div class="hero"><div class="hrow">
        <div class="logo"><img src="${logo}"/></div>
        <div><h1>Extrato ${esc(BRAND.name)}</h1><div class="sub">${esc(companyLine)}</div></div>
        <div class="meta"><span class="pill">${esc(scope)}</span><br/>Gerado em ${esc(new Date().toLocaleString('pt-BR'))}</div>
      </div></div>
      <div class="cards">
        <div class="card"><small>Entradas</small><b class="in">+${esc(brl(totIn))}</b></div>
        <div class="card"><small>Saídas</small><b>-${esc(brl(totOut))}</b></div>
        <div class="card"><small>Resultado</small><b style="color:${res >= 0 ? '#1a9b45' : '#c62828'}">${res >= 0 ? '+' : '-'}${esc(brl(Math.abs(res)))}</b></div>
        <div class="card"><small>Movimentações</small><b>${filtered.length.toLocaleString('pt-BR')}</b></div>
      </div>
      <div class="wrap">
        ${partial ? `<div class="warn">Mostrando as ${filtered.length.toLocaleString('pt-BR')} movimentações mais recentes. Escolha um período (Hoje, 7 ou 30 dias) para o extrato completo.</div>` : ''}
        ${days || '<p style="color:#888;text-align:center;margin-top:30px">Nenhuma movimentação no período.</p>'}
        <div class="foot"><span>Documento gerado pelo app ${esc(BRAND.name)}</span><span>© ${esc(BRAND.copyright)}</span></div>
      </div>
    </body></html>`;
    try {
      const { uri } = await Print.printToFileAsync({ html });
      setSheet(false);
      await Sharing.shareAsync(uri, { mimeType: 'application/pdf', UTI: 'com.adobe.pdf', dialogTitle: `Extrato ${BRAND.name}` });
    } catch {
      Alert.alert('Não foi possível gerar o PDF', 'Tente de novo em instantes.');
    } finally {
      setBusy(null);
    }
  };

  /** Extrato em texto, organizado, com emojis que aparecem em qualquer Android (sem os mais novos) */
  const shareStatement = async () => {
    setBusy('txt');
    const LINE = '━━━━━━━━━━━━━━━━━━';
    const res = totIn - totOut;
    const out: string[] = [
      `💰 *EXTRATO ${BRAND.name.toUpperCase()}*`,
      company?.name ? `🏢 ${companyLine}` : '',
      `📅 Período: ${scope}`,
      `🕐 Gerado em ${new Date().toLocaleString('pt-BR')}`,
      LINE,
      `📈 Entradas: *+${brl(totIn)}*`,
      `📉 Saídas: *-${brl(totOut)}*`,
      `💼 Resultado: *${res >= 0 ? '+' : '-'}${brl(Math.abs(res))}*`,
      `🔢 Movimentações: ${filtered.length.toLocaleString('pt-BR')}`,
      LINE,
    ].filter(Boolean);
    let lines = 0;
    const MAX = 1200;
    for (const g of byDay()) {
      if (lines >= MAX) break;
      out.push('', `📆 *${g.day.toUpperCase()}*`);
      for (const m of g.list) {
        if (lines++ >= MAX) break;
        const o = m.net_amount < 0 || m.kind === 'WITHDRAW';
        const fail = failedWd(m);
        out.push(
          `${fail ? '❌' : o ? '⬆️' : '⬇️'} ${timeLabel(m.occurred_at)}  ${o ? '-' : '+'}${brl(Math.abs(m.net_amount))}`,
          `      ${nameOf(m)} · ${typeOf(m)}`
        );
      }
    }
    if (filtered.length > MAX) out.push('', `➕ Mais ${(filtered.length - MAX).toLocaleString('pt-BR')} movimentações: exporte em PDF para ver todas.`);
    if (partial) out.push('', 'ℹ️ Mostrando as mais recentes. Escolha Hoje, 7 ou 30 dias para o extrato completo.');
    out.push('', LINE, `© ${BRAND.copyright}`);
    setSheet(false);
    setBusy(null);
    await Share.share({ message: out.join('\n') });
  };

  const shareMenu = () => {
    tap();
    setSheet(true);
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
        refreshControl={
          <RefreshControl tintColor={C.red} colors={[C.red]} progressBackgroundColor="#2C2D35" size="large" refreshing={r.refreshing} onRefresh={r.run} />
        }
        initialNumToRender={14}
        maxToRenderPerBatch={20}
        windowSize={9}
        removeClippedSubviews
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
            {syncing && !loading ? (
              <View style={xs.sync}>
                <ActivityIndicator size="small" color={C.red} />
                <Text style={xs.syncText}>Somando {filtered.length.toLocaleString('pt-BR')} movimentações do período…</Text>
              </View>
            ) : null}
            <ErrorBanner message={error} />
            {loading ? <ActivityIndicator color={C.red} style={{ marginTop: 40 }} /> : null}
          </View>
        }
        ListEmptyComponent={
          loading ? undefined : (
            <Card style={{ marginTop: 16 }}>
              <Empty icon="swap-vertical" title="Nada por aqui ainda" text="Quando houver movimentações, elas aparecem aqui." />
            </Card>
          )
        }
        ListFooterComponent={more ? <ActivityIndicator color={C.red} style={{ marginVertical: 20 }} /> : undefined}
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

      <RefreshPill stamp={r.stamp} top={ins.top + 8} />

      <Modal visible={sheet} transparent animationType="slide" onRequestClose={() => {
          if (!busy) setSheet(false);
        }} statusBarTranslucent>
        <Pressable style={xs.backdrop} onPress={() => !busy && setSheet(false)} />
        <View style={[xs.sheet, { paddingBottom: ins.bottom + 18 }]}>
          <View style={xs.grab} />
          <View style={{ flexDirection: 'row', alignItems: 'center', gap: 12 }}>
            <LinearGradient colors={[BRAND.accentDeep, BRAND.accent]} style={xs.sheetLogo}>
              <Image source={LOGO} style={{ width: 30, height: 28 }} contentFit="contain" />
            </LinearGradient>
            <View style={{ flex: 1 }}>
              <Text style={xs.sheetTitle}>Exportar extrato</Text>
              <Text style={xs.sheetSub}>{scope} · {filtered.length.toLocaleString('pt-BR')} movimentações</Text>
            </View>
          </View>
          <View style={xs.sheetTotals}>
            <View style={{ flex: 1 }}>
              <Text style={xs.totLabel}>Entradas</Text>
              <Text style={[xs.sheetVal, { color: C.green }]}>+{brl(totIn)}</Text>
            </View>
            <View style={{ flex: 1, alignItems: 'flex-end' }}>
              <Text style={xs.totLabel}>Saídas</Text>
              <Text style={xs.sheetVal}>-{brl(totOut)}</Text>
            </View>
          </View>
          {syncing ? <Text style={xs.sheetNote}>Ainda somando o período… espere terminar para exportar tudo.</Text> : null}
          <Pressable disabled={!!busy || syncing} onPress={sharePdf} style={({ pressed }) => [xs.opt, pressed && { opacity: 0.8 }, (busy || syncing) && { opacity: 0.5 }]}>
            <LinearGradient colors={[BRAND.accent, BRAND.accentDeep]} start={{ x: 0, y: 0 }} end={{ x: 1, y: 1 }} style={xs.optIcon}>
              {busy === 'pdf' ? <ActivityIndicator color="#fff" /> : <Ionicons name="document-text" size={22} color="#fff" />}
            </LinearGradient>
            <View style={{ flex: 1 }}>
              <Text style={xs.optTitle}>PDF com a marca</Text>
              <Text style={xs.optSub}>Documento bonito, pronto pra enviar ou imprimir</Text>
            </View>
            <Ionicons name="chevron-forward" size={18} color={C.dim} />
          </Pressable>
          <Pressable disabled={!!busy || syncing} onPress={shareStatement} style={({ pressed }) => [xs.opt, pressed && { opacity: 0.8 }, (busy || syncing) && { opacity: 0.5 }]}>
            <View style={[xs.optIcon, { backgroundColor: '#1F2A22' }]}>
              {busy === 'txt' ? <ActivityIndicator color={C.green} /> : <Ionicons name="chatbubble-ellipses" size={21} color={C.green} />}
            </View>
            <View style={{ flex: 1 }}>
              <Text style={xs.optTitle}>Texto organizado</Text>
              <Text style={xs.optSub}>Com emojis, ideal pro WhatsApp</Text>
            </View>
            <Ionicons name="chevron-forward" size={18} color={C.dim} />
          </Pressable>
          <Pressable disabled={!!busy} onPress={() => setSheet(false)} style={xs.cancel}>
            <Text style={xs.cancelText}>Cancelar</Text>
          </Pressable>
        </View>
      </Modal>
    </View>
  );
}

const MovementRow = React.memo(function MovementRow({ m }: { m: Movement }) {
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
});

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
  sync: { flexDirection: 'row', alignItems: 'center', gap: 8, marginTop: 10 },
  syncText: { color: C.dim, fontSize: 11.5 },
  backdrop: { flex: 1, backgroundColor: 'rgba(0,0,0,0.6)' },
  sheet: { backgroundColor: '#121317', borderTopLeftRadius: 28, borderTopRightRadius: 28, padding: 20, paddingTop: 10, gap: 12, borderWidth: StyleSheet.hairlineWidth, borderColor: 'rgba(255,255,255,0.1)' },
  grab: { alignSelf: 'center', width: 42, height: 5, borderRadius: 3, backgroundColor: 'rgba(255,255,255,0.18)', marginBottom: 8 },
  sheetLogo: { width: 48, height: 48, borderRadius: 15, alignItems: 'center', justifyContent: 'center' },
  sheetTitle: { color: C.text, fontSize: 19, fontWeight: '800' },
  sheetSub: { color: C.muted, fontSize: 13, marginTop: 2 },
  sheetTotals: { flexDirection: 'row', backgroundColor: '#1A1B20', borderRadius: 16, padding: 14 },
  sheetVal: { color: C.text, fontSize: 16, fontWeight: '800', marginTop: 2, fontVariant: ['tabular-nums'] },
  sheetNote: { color: C.dim, fontSize: 12, textAlign: 'center' },
  opt: { flexDirection: 'row', alignItems: 'center', gap: 14, backgroundColor: '#1A1B20', borderRadius: 18, padding: 14, borderWidth: StyleSheet.hairlineWidth, borderColor: 'rgba(255,255,255,0.08)' },
  optIcon: { width: 46, height: 46, borderRadius: 14, alignItems: 'center', justifyContent: 'center' },
  optTitle: { color: C.text, fontSize: 16, fontWeight: '700' },
  optSub: { color: C.muted, fontSize: 12.5, marginTop: 2 },
  cancel: { height: 50, alignItems: 'center', justifyContent: 'center' },
  cancelText: { color: C.muted, fontSize: 16, fontWeight: '600' },
  totValue: { color: C.text, fontSize: 17, fontWeight: '800', marginTop: 2, fontVariant: ['tabular-nums'] },
});
