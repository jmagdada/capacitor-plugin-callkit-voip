import type { PluginListenerHandle } from '@capacitor/core';

export interface CallKitVoipPlugin {
  register(options: { userToken: string }): Promise<void>;

  getVoipToken(): Promise<CallToken>;

  requestPhoneNumbersPermission(): Promise<{ granted: boolean; message: string }>;

  checkPhoneAccountStatus(): Promise<PhoneAccountStatus>;

  openPhoneAccountSettings(): Promise<void>;

  requestNotificationPermission(): Promise<void>;

  answerCall(options: { uuid: string }): Promise<void>;

  rejectCall(options: { uuid: string }): Promise<void>;

  hangupCall(options: { uuid: string }): Promise<void>;

  callConnected(options: { uuid: string }): Promise<void>;

  endCall(options: { uuid: string }): Promise<void>;

  getCallMetrics(options: { uuid: string }): Promise<CallMetrics>;

  addListener(
      eventName: 'registration',
      listenerFunc: (token:CallToken)   => void
  ): Promise<PluginListenerHandle> & PluginListenerHandle;

  addListener(
      eventName: 'callAnswered',
      listenerFunc: (callData: CallData)  => void
  ): Promise<PluginListenerHandle> & PluginListenerHandle;

  addListener(
      eventName: 'callConnected',
      listenerFunc: (callData: CallData) => void
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

  type?: string;
  call_type?: string;
  channel_id?: string;
  uuid?: string;
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