package com.linearlite.server.dto;

/** 宿主确认执行单元及关联写入进程已经停止。 */
public record CollaborationStopConfirmation(boolean stopped, String detail) { }
