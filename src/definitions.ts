import type { PluginListenerHandle } from '@capacitor/core';

export interface CallKitVoipPlugin {
  register(): Promise<void>;

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
  /**
   * Call Hostname
   */
  host?:string;
  /**
   * Call Username
   */
  username?:string;
  /**
   * Call Password
   */
  secret?:string;
}