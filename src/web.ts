import { WebPlugin } from '@capacitor/core';

import type { CallKitVoipPlugin, PhoneAccountStatus, CallMetrics, CallToken } from './definitions';

export class CallKitVoipWeb extends WebPlugin implements CallKitVoipPlugin {
  async register(): Promise<void> {
    console.log('CallKitVoip.register - not supported on web');
    return;
  }

  async getVoipToken(): Promise<CallToken> {
    console.log('CallKitVoip.getVoipToken - not supported on web');
    throw new Error('VoIP token not available on web platform');
  }

  async requestPhoneNumbersPermission(): Promise<{ granted: boolean; message: string }> {
    console.log('CallKitVoip.requestPhoneNumbersPermission - not supported on web');
    return {
      granted: false,
      message: 'Phone permissions not available on web platform'
    };
  }

  async checkPhoneAccountStatus(): Promise<PhoneAccountStatus> {
    console.log('CallKitVoip.checkPhoneAccountStatus - not supported on web');
    return {
      supported: false,
      enabled: false,
      message: 'PhoneAccount not available on web platform',
      canOpenSettings: false
    };
  }

  async openPhoneAccountSettings(): Promise<void> {
    console.log('CallKitVoip.openPhoneAccountSettings - not supported on web');
    throw new Error('PhoneAccount settings not available on web platform');
  }

  async requestNotificationPermission(): Promise<void> {
    console.log('CallKitVoip.requestNotificationPermission - not supported on web');
    return;
  }

  async answerCall(_options: { connectionId: string }): Promise<void> {
    console.log('CallKitVoip.answerCall - not supported on web');
    return;
  }

  async rejectCall(_options: { connectionId: string }): Promise<void> {
    console.log('CallKitVoip.rejectCall - not supported on web');
    return;
  }

  async hangupCall(_options: { connectionId: string }): Promise<void> {
    console.log('CallKitVoip.hangupCall - not supported on web');
    return;
  }

  async getCallMetrics(_options: { connectionId: string }): Promise<CallMetrics> {
    console.log('CallKitVoip.getCallMetrics - not supported on web');
    return {};
  }
}
