import React, { useCallback, useEffect, useState } from 'react';
import { Pressable, StyleSheet, Text, View } from 'react-native';
import { router } from 'expo-router';
import { Image } from 'expo-image';
import { Ionicons } from '@expo/vector-icons';
import * as Notifications from 'expo-notifications';
import { Card, Empty, IconButton, IconName, Screen, Segmented } from '../src/components/ui';
import { api, AppNotification } from '../src/lib/api';
import { dayLabel, timeLabel } from '../src/lib/format';
import { C } from '../src/theme';
import { BRAND, A } from '../src/brand';

/** Central de avisos: Pix recebidos, saques e anúncios do painel (o mesmo sininho do site) */

type Filter = 'all' | 'money' | 'news';

function kindOf(n: AppNotification): { icon: IconName; color: string; bg: string } {
  if (n.category === 'INCOMING') return { icon: 'arrow-down', color: C.green, bg: C.greenSoft };
  if (n.category === 'OUTGOING') {
    const bad = /recus|falh|cancel/i.test(n.title);
    return bad
      ? { icon: 'close', color: C.redLight, bg: C.redSoft }
      : { icon: 'arrow-up', color: C.silver, bg: 'rgba(255,255,255,0.07)' };
  }
  return { icon: 'megaphone', color: C.redLight, bg: C.redSoft };
}

export default function Avisos() {
  const [items, setItems] = useState<AppNotification[] | null>(null);
  const [filter, setFilter] = useState<Filter>('all');

  const load = useCallback(async () => {
    const [a, b] = await Promise.all([api.notifications(50).catch(() => []), api.announcements().catch(() => [])]);
    const all = [...a, ...b].sort((x, y) => (x.created_at < y.created_at ? 1 : -1));
    setItems(all);
    return all;
  }, []);

  useEffect(() => {
    load().then((all) => {
      // Abriu a central: marca tudo como lido (igual o painel) e zera o número no ícone
      const unread = all.filter((n) => !n.read_at);
      if (!unread.length) return;
      api.markAllRead().catch(() => {});
      unread.filter((n) => n.announcement_id).forEach((n) => api.markRead(n.id).catch(() => {}));
      Notifications.setBadgeCountAsync(0).catch(() => {});
    });
  }, [load]);

  const shown = (items ?? []).filter((n) =>
    filter === 'all' ? true : filter === 'news' ? n.category === 'ANNOUNCEMENT' || !!n.announcement_id : n.category !== 'ANNOUNCEMENT' && !n.announcement_id
  );

  return (
    <Screen onRefresh={load} bottomPad={60}>
      <View style={st.top}>
        <Text style={st.title}>Avisos</Text>
        <IconButton icon="close" label="Fechar" onPress={() => router.back()} />
      </View>

      <Segmented
        value={filter}
        onChange={setFilter}
        options={[
          { value: 'all', label: 'Tudo' },
          { value: 'money', label: 'Movimentações' },
          { value: 'news', label: 'Anúncios' },
        ]}
      />

      <View style={{ height: 16 }} />

      {items === null ? (
        <Text style={st.loading}>Carregando…</Text>
      ) : shown.length === 0 ? (
        <Card>
          <Empty icon="notifications-off-outline" title="Nenhum aviso por aqui" text="Pix recebidos, saques e anúncios aparecem nesta lista." />
        </Card>
      ) : (
        shown.map((n) => {
          const k = kindOf(n);
          return (
            <Pressable
              key={n.id}
              onPress={() => {
                if (n.category === 'INCOMING' || n.category === 'OUTGOING') router.navigate('/(tabs)/extrato');
              }}
              style={({ pressed }) => [st.item, !n.read_at && st.itemUnread, { opacity: pressed ? 0.8 : 1 }]}
            >
              <View style={{ flexDirection: 'row' }}>
                <View style={[st.icon, { backgroundColor: k.bg }]}>
                  <Ionicons name={k.icon} size={18} color={k.color} />
                </View>
                <View style={{ flex: 1, marginLeft: 12 }}>
                  <View style={{ flexDirection: 'row', alignItems: 'center' }}>
                    <Text style={st.itemTitle} numberOfLines={2}>
                      {n.title}
                    </Text>
                    {!n.read_at ? <View style={st.unreadDot} /> : null}
                  </View>
                  {n.message && n.message !== n.title ? <Text style={st.itemText}>{n.message}</Text> : null}
                  <Text style={st.itemTime}>
                    {dayLabel(n.created_at)}, {timeLabel(n.created_at)}
                  </Text>
                </View>
              </View>
              {n.banner_url ? (
                <Image source={{ uri: n.banner_url }} style={st.banner} contentFit="cover" transition={200} />
              ) : null}
            </Pressable>
          );
        })
      )}
    </Screen>
  );
}

const st = StyleSheet.create({
  top: { flexDirection: 'row', alignItems: 'center', justifyContent: 'space-between', marginBottom: 18, marginTop: 6 },
  title: { color: C.text, fontSize: 32, fontWeight: '800', letterSpacing: -0.6 },
  loading: { color: C.muted, textAlign: 'center', marginTop: 40 },
  item: {
    backgroundColor: '#111114',
    borderRadius: 20,
    padding: 16,
    marginBottom: 10,
    borderWidth: StyleSheet.hairlineWidth,
    borderColor: 'rgba(255,255,255,0.08)',
  },
  itemUnread: { borderColor: A(0.45), backgroundColor: BRAND.tint },
  icon: { width: 38, height: 38, borderRadius: 19, alignItems: 'center', justifyContent: 'center' },
  itemTitle: { color: C.text, fontSize: 16, fontWeight: '700', flexShrink: 1 },
  unreadDot: { width: 8, height: 8, borderRadius: 4, backgroundColor: C.red, marginLeft: 8 },
  itemText: { color: C.sub, fontSize: 14.5, marginTop: 3, lineHeight: 20 },
  itemTime: { color: C.muted, fontSize: 12.5, marginTop: 6 },
  banner: { width: '100%', aspectRatio: 2, borderRadius: 14, marginTop: 12, backgroundColor: '#1A1A1E' },
});
