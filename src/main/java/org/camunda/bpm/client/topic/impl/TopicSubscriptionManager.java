/* Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.camunda.bpm.client.topic.impl;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.camunda.bpm.client.ClientBackoffStrategy;
import org.camunda.bpm.client.exception.ExternalTaskClientException;
import org.camunda.bpm.client.impl.EngineClient;
import org.camunda.bpm.client.impl.EngineClientException;
import org.camunda.bpm.client.impl.ExternalTaskClientLogger;
import org.camunda.bpm.client.task.ExternalTask;
import org.camunda.bpm.client.task.ExternalTaskHandler;
import org.camunda.bpm.client.task.impl.ExternalTaskImpl;
import org.camunda.bpm.client.task.impl.ExternalTaskServiceImpl;
import org.camunda.bpm.client.topic.TopicSubscription;
import org.camunda.bpm.client.topic.impl.dto.TopicRequestDto;
import org.camunda.bpm.client.variable.impl.TypedValueField;
import org.camunda.bpm.client.variable.impl.TypedValues;
import org.camunda.bpm.client.variable.impl.VariableValue;

/**
 * @author Tassilo Weidner
 */
public class TopicSubscriptionManager implements Runnable {

  protected static final TopicSubscriptionManagerLogger LOG = ExternalTaskClientLogger.TOPIC_SUBSCRIPTION_MANAGER_LOGGER;

  protected final Object MONITOR = new Object();

  protected ExternalTaskServiceImpl externalTaskService;

  protected EngineClient engineClient;

  protected List<TopicSubscription> subscriptions;
  protected List<TopicRequestDto> taskTopicRequests;
  protected Map<String, ExternalTaskHandler> externalTaskHandlers;

  protected volatile boolean isRunning;
  protected Thread thread;

  protected ClientBackoffStrategy backoffStrategy;

  protected TypedValues typedValues;

  protected long clientLockDuration;

  public TopicSubscriptionManager(EngineClient engineClient, TypedValues typedValues, long clientLockDuration) {
    this.engineClient = engineClient;
    this.subscriptions = new CopyOnWriteArrayList<>();
    this.taskTopicRequests = new ArrayList<>();
    this.externalTaskHandlers = new HashMap<>();
    this.isRunning = false;
    this.clientLockDuration = clientLockDuration;
    this.typedValues = typedValues;
    externalTaskService = new ExternalTaskServiceImpl(engineClient);
  }

  public void run() {
    while (isRunning) {
      try {
        acquire();
      }
      catch (Throwable e) {
        LOG.exceptionWhileAcquiringTasks(e);
      }
    }
  }

  protected void acquire() {
    taskTopicRequests.clear();
    externalTaskHandlers.clear();
    subscriptions.forEach(this::prepareAcquisition);

    if (!taskTopicRequests.isEmpty()) {
      List<ExternalTask> externalTasks = fetchAndLock(taskTopicRequests);

      externalTasks.forEach(externalTask -> {
        String topicName = externalTask.getTopicName();
        ExternalTaskHandler taskHandler = externalTaskHandlers.get(topicName);

        if (taskHandler != null) {
          handleExternalTask(externalTask, taskHandler);
        }
        else {
          LOG.taskHandlerIsNull(topicName);
        }
      });

      if (backoffStrategy != null) {
        runBackoffStrategy(externalTasks.isEmpty());
      }
    }
  }

  protected void prepareAcquisition(TopicSubscription subscription) {
    TopicRequestDto taskTopicRequest = TopicRequestDto.fromTopicSubscription(subscription, clientLockDuration);
    taskTopicRequests.add(taskTopicRequest);

    String topicName = subscription.getTopicName();
    ExternalTaskHandler externalTaskHandler = subscription.getExternalTaskHandler();
    externalTaskHandlers.put(topicName, externalTaskHandler);
  }

  protected List<ExternalTask> fetchAndLock(List<TopicRequestDto> subscriptions) {
    List<ExternalTask> externalTasks = Collections.emptyList();

    try {
      externalTasks = engineClient.fetchAndLock(subscriptions);
    } catch (EngineClientException e) {
      LOG.exceptionWhilePerformingFetchAndLock(e);
    }

    return externalTasks;
  }

  protected void handleExternalTask(ExternalTask externalTask, ExternalTaskHandler taskHandler) {
    ExternalTaskImpl task = (ExternalTaskImpl) externalTask;

    Map<String, TypedValueField> variables = task.getVariables();
    Map<String, VariableValue> wrappedVariables = typedValues.wrapVariables(variables);
    task.setReceivedVariableMap(wrappedVariables);

    try {
      taskHandler.execute(task, externalTaskService);
    } catch (ExternalTaskClientException e) {
      LOG.exceptionOnExternalTaskServiceMethodInvocation(task.getTopicName(), e);
    } catch (Throwable e) {
      LOG.exceptionWhileExecutingExternalTaskHandler(task.getTopicName(), e);
    }
  }

  public void stop() {
    synchronized (MONITOR) {
      if (!isRunning || thread == null) {
        return;
      }

      isRunning = false;

      if (backoffStrategy != null) {
        resumeBackoffStrategy();
      }

      try {
        thread.join();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
        LOG.exceptionWhileShuttingDown(e);
      }
    }
  }

  public void start() {
    synchronized (MONITOR) {
      if (isRunning && thread != null) {
        return;
      }

      isRunning = true;
      thread = new Thread(this, TopicSubscriptionManager.class.getSimpleName());
      thread.start();
    }
  }

  protected synchronized void subscribe(TopicSubscriptionImpl subscription) {
    checkTopicNameAlreadySubscribed(subscription.getTopicName());

    subscriptions.add(subscription);

    if (backoffStrategy != null) {
      resumeBackoffStrategy();
    }
  }

  protected void checkTopicNameAlreadySubscribed(String topicName) {
    subscriptions.forEach(subscription -> {
      if (subscription.getTopicName().equals(topicName)) {
        throw LOG.topicNameAlreadySubscribedException(topicName);
      }
    });
  }

  protected void unsubscribe(TopicSubscriptionImpl subscription) {
    subscriptions.remove(subscription);
  }

  public EngineClient getEngineClient() {
    return engineClient;
  }

  public List<TopicSubscription> getSubscriptions() {
    return subscriptions;
  }

  public boolean isRunning() {
    return isRunning;
  }

  public void setBackoffStrategy(ClientBackoffStrategy backOffStrategy) {
    this.backoffStrategy = backOffStrategy;
  }

  protected void runBackoffStrategy(boolean isExternalTasksEmpty) {
    try {
      if (isExternalTasksEmpty) {
        backoffStrategy.suspend();
      } else {
        backoffStrategy.reset();
      }
    } catch (Throwable e) {
      LOG.exceptionWhileExecutingBackoffStrategyMethod(e);
    }
  }

  protected void resumeBackoffStrategy() {
    try {
      backoffStrategy.resume();
    } catch (Throwable e) {
      LOG.exceptionWhileExecutingBackoffStrategyMethod(e);
    }
  }

}
