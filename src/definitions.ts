import type { PluginListenerHandle } from '@capacitor/core';

export interface CallKitVoipPlugin {
  register(options: { userToken: string }): Promise<void>;

  getVoipToken(): Promise<CallToken>;

  requestPhoneNumbersPermission(): Promise<{ granted: boolean; message: string }>;

  checkPhoneAccountStatus(): Promise<PhoneAccountStatus>;

  openPhoneAccountSettings(): Promise<void>;

  requestNotificationPermission(): Promise<void>;

  answerCall(options: { connectionId: string }): Promise<void>;

  rejectCall(options: { connectionId: string }): Promise<void>;

  hangupCall(options: { connectionId: string }): Promise<void>;

  getCallMetrics(options: { connectionId: string }): Promise<CallMetrics>;

  addListener(
      eventName: 'registration',
      listenerFunc: (token:CallToken)   => void
  ): Promise<PluginListenerHandle> & PluginListenerHandle;

  addListener(
      eventName: 'callAnswered',
      listenerFunc: (callData: CallData)  => void
  ): Promise<PluginListenerHandle> & PluginListenerHandle;

  addListener(
      eventName: 'callStarted',
      listenerFunc: (callData: CallData) => void
  ): Promise<PluginListenerHandle> & PluginListenerHandle;

  addListener(
      eventName: 'callEnded',
      listenerFunc: (callData: CallData) => void
  ): Promise<PluginListenerHandle> & PluginListenerHandle;

  addListener(
      eventName: 'callRejected',
      listenerFunc: (callData: CallData) => void
  ): Promise<PluginListenerHandle> & PluginListenerHandle;

  addListener(
      eventName: 'callCancelled',
      listenerFunc: (callData: CallData) => void
  ): Promise<PluginListenerHandle> & PluginListenerHandle;

  addListener(
      eventName: 'error',
      listenerFunc: (error: CallKitError) => void
  ): Promise<PluginListenerHandle> & PluginListenerHandle;
}




export type CallType = 'video' | 'audio';

export interface CallToken {
  /**
   * VOIP Token
   */
  value: string;
}

export interface CallData {
  /**
   * Call ID
   */
  callId:string;
  /**
   * Call Type
   */
  media?: CallType;
  /**
   * Call duration
   */
  duration?:string;
  /**
   * Call Booking ID (Extension)
   */
  bookingId?:string;
}

export interface PhoneAccountStatus {
  supported: boolean;
  enabled: boolean;
  message?: string;
  instructions?: string;
  canOpenSettings: boolean;
}

export interface CallKitError {
  code: string;
  message: string;
}

export interface CallMetrics {
  startTime?: number;
  endTime?: number;
  duration?: number;
  endReason?: string;
  error?: string;
  retryCount?: number;
}