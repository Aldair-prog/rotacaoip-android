import React, { useCallback, useState } from 'react';
import { Platform, Text } from 'react-native';
import { useFocusEffect } from 'expo-router';
import { Ionicons } from '@expo/vector-icons';
import { Card, Row } from './ui';
import { batteryRestricted, openBatterySettings } from '../lib/notify';
import { C } from '../theme';

/**
 * Só no Android: a economia de bateria pode pausar o app e atrasar os avisos de Pix.
 * Mostra o status e leva direto ao ajuste certo.
 */
export function BackgroundRow() {
  const [restricted, setRestricted] = useState(false);
  useFocusEffect(
    useCallback(() => {
      setRestricted(batteryRestricted());
    }, [])
  );
  if (Platform.OS !== 'android') return null;
  return (
    <Card style={{ paddingVertical: 4, marginTop: 10 }}>
      <Row
        icon="battery-charging"
        iconColor={restricted ? C.orange : C.green}
        iconBg={restricted ? C.orangeSoft : C.greenSoft}
        title="Avisos na hora"
        subtitle={restricted ? 'Toque para liberar o app da economia de bateria' : 'Liberado: os avisos chegam na hora'}
        right={
          restricted ? <Ionicons name="chevron-forward" size={18} color={C.dim} /> : <Text style={{ color: C.green, fontWeight: '700' }}>OK</Text>
        }
        onPress={restricted ? openBatterySettings : undefined}
        last
      />
    </Card>
  );
}
