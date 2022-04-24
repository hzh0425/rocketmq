/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.rocketmq.namesrv.controller.manager.event;

import java.util.ArrayList;
import java.util.List;
import org.apache.rocketmq.common.protocol.ResponseCode;

public class ControllerResult<T> {
    private final List<EventMessage> events;
    private final T response;
    private int responseCode = ResponseCode.SYSTEM_ERROR;

    public ControllerResult(T response) {
        this.events = new ArrayList<>();
        this.response = response;
    }

    public ControllerResult(List<EventMessage> events, T response) {
        this.events = new ArrayList<>(events);
        this.response = response;
    }

    public List<EventMessage> getEvents() {
        return new ArrayList<>(events);
    }

    public T getResponse() {
        return response;
    }

    public void setResponseCode(int responseCode) {
        this.responseCode = responseCode;
    }

    public int getResponseCode() {
        return responseCode;
    }

    public static <T> ControllerResult<T> of(List<EventMessage> events, T response) {
        return new ControllerResult<>(events, response);
    }

    public void addEvent(EventMessage event) {
        this.events.add(event);
    }

    @Override public String toString() {
        return "ControllerResult{" +
            "events=" + events +
            ", response=" + response +
            '}';
    }
}
