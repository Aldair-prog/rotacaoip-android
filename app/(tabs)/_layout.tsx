import React from 'react';
import { NativeTabs } from 'expo-router/native-tabs';
import { C } from '../../src/theme';

// Barra de abas nativa do iOS (efeito de vidro "Liquid Glass" no iOS 26)
export default function TabsLayout() {
  return (
    <NativeTabs tintColor={C.red} minimizeBehavior="onScrollDown">
      <NativeTabs.Trigger name="index">
        <NativeTabs.Trigger.Icon sf={{ default: 'house', selected: 'house.fill' }} md="home" />
        <NativeTabs.Trigger.Label>Início</NativeTabs.Trigger.Label>
      </NativeTabs.Trigger>
      <NativeTabs.Trigger name="extrato">
        <NativeTabs.Trigger.Icon sf={{ default: 'list.bullet.rectangle', selected: 'list.bullet.rectangle.fill' }} md="receipt_long" />
        <NativeTabs.Trigger.Label>Extrato</NativeTabs.Trigger.Label>
      </NativeTabs.Trigger>
      <NativeTabs.Trigger name="receber">
        <NativeTabs.Trigger.Icon sf="qrcode" md="qr_code" />
        <NativeTabs.Trigger.Label>Receber</NativeTabs.Trigger.Label>
      </NativeTabs.Trigger>
      <NativeTabs.Trigger name="enviar">
        <NativeTabs.Trigger.Icon sf={{ default: 'paperplane', selected: 'paperplane.fill' }} md="send" />
        <NativeTabs.Trigger.Label>Enviar</NativeTabs.Trigger.Label>
      </NativeTabs.Trigger>
      <NativeTabs.Trigger name="conta">
        <NativeTabs.Trigger.Icon sf={{ default: 'person.crop.circle', selected: 'person.crop.circle.fill' }} md="account_circle" />
        <NativeTabs.Trigger.Label>Conta</NativeTabs.Trigger.Label>
      </NativeTabs.Trigger>
    </NativeTabs>
  );
}
