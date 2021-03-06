/*-
 * ---license-start
 * EU-Federation-Gateway-Service / efgs-federation-gateway
 * ---
 * Copyright (C) 2020 T-Systems International GmbH and all other contributors
 * ---
 * Licensed under the Apache License, Version 2.0 (the "License");
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
 * ---license-end
 */

package eu.interop.federationgateway.service;

import eu.interop.federationgateway.TestData;
import eu.interop.federationgateway.config.EfgsProperties;
import eu.interop.federationgateway.entity.CallbackSubscriptionEntity;
import eu.interop.federationgateway.entity.CallbackTaskEntity;
import eu.interop.federationgateway.entity.CertificateEntity;
import eu.interop.federationgateway.entity.DiagnosisKeyBatchEntity;
import eu.interop.federationgateway.repository.CallbackSubscriptionRepository;
import eu.interop.federationgateway.repository.CallbackTaskRepository;
import eu.interop.federationgateway.repository.CertificateRepository;
import eu.interop.federationgateway.repository.DiagnosisKeyBatchRepository;
import java.io.IOException;
import java.net.MalformedURLException;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import lombok.extern.slf4j.Slf4j;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import okhttp3.mockwebserver.RecordedRequest;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.web.reactive.function.client.WebClient;

@Slf4j
@RunWith(SpringRunner.class)
@SpringBootTest
public class CallbackTaskExecutorServiceTest {

  CallbackTaskExecutorService callbackTaskExecutorService;

  @Autowired
  EfgsProperties efgsProperties;

  CallbackService callbackServiceMock;

  @Autowired
  CallbackService callbackService;

  @Autowired
  CallbackTaskRepository callbackTaskRepository;

  @Autowired
  CertificateService certificateService;

  @Autowired
  CertificateRepository certificateRepository;

  @Autowired
  CallbackSubscriptionRepository callbackSubscriptionRepository;

  @Autowired
  DiagnosisKeyBatchRepository diagnosisKeyBatchRepository;

  @Autowired
  WebClient webClient;

  EfgsProperties.Callback callbackProperties;

  MockWebServer mockWebServer;

  String mockCallbackUrl;

  @Before
  public void setup() throws IOException {
    mockWebServer = new MockWebServer();
    mockWebServer.start();

    mockCallbackUrl = "http://localhost:" + mockWebServer.getPort();

    callbackServiceMock = Mockito.mock(CallbackService.class);

    Mockito.doAnswer(a -> {
      callbackService.deleteCallbackSubscription(a.getArgument(0));
      return null;
    }).when(callbackServiceMock).deleteCallbackSubscription(Mockito.any(CallbackSubscriptionEntity.class));

    Mockito.doReturn(true)
      .when(callbackServiceMock).checkUrl(Mockito.anyString(), Mockito.anyString());

    callbackTaskExecutorService = new CallbackTaskExecutorService(
      efgsProperties, webClient, callbackServiceMock, callbackTaskRepository, certificateService);
  }

  @After
  public void stopWebServer() throws IOException {
    mockWebServer.shutdown();
  }

  @Before
  @After
  public void cleanupDB() {
    callbackTaskRepository.deleteAll();
    callbackSubscriptionRepository.deleteAll();
    certificateRepository.deleteAll();
    diagnosisKeyBatchRepository.deleteAll();
  }

  @Test
  public void callbackExecutorShouldDeleteSubscriptionIfUrlCheckFails() {
    CallbackSubscriptionEntity subscription1 = createSubscription(TestData.CALLBACK_ID_FIRST);
    DiagnosisKeyBatchEntity batch = createDiagnosisKeyBatch("BT1", ZonedDateTime.now(ZoneOffset.UTC));
    createCallbackTask(subscription1, batch, null);

    Mockito.when(callbackServiceMock.checkUrl(Mockito.anyString(), Mockito.anyString()))
      .thenReturn(false);

    callbackTaskExecutorService.execute();

    Assert.assertEquals(0, callbackTaskRepository.count());
    Assert.assertEquals(0, callbackSubscriptionRepository.count());
  }

  @Test
  public void callbackExecutorShouldDeleteSubscriptionIfCertificateIsMissing() {
    CallbackSubscriptionEntity subscription1 = createSubscription(TestData.CALLBACK_ID_FIRST);
    DiagnosisKeyBatchEntity batch = createDiagnosisKeyBatch("BT1", ZonedDateTime.now(ZoneOffset.UTC));
    createCallbackTask(subscription1, batch, null);

    certificateRepository.deleteAll();

    callbackTaskExecutorService.execute();

    Assert.assertEquals(0, callbackTaskRepository.count());
    Assert.assertEquals(0, callbackSubscriptionRepository.count());
  }

  @Test
  public void callbackExecutorShouldCallCallbackURL() throws InterruptedException, MalformedURLException {
    CallbackSubscriptionEntity subscription1 = createSubscription(TestData.CALLBACK_ID_FIRST);
    DiagnosisKeyBatchEntity batch = createDiagnosisKeyBatch("BT1", ZonedDateTime.now(ZoneOffset.UTC));
    createCallbackTask(subscription1, batch, null);
    CertificateEntity certificate = insertCallbackCertificate();

    mockWebServer.enqueue(new MockResponse().setResponseCode(200));

    callbackTaskExecutorService.execute();

    RecordedRequest request = mockWebServer.takeRequest();
    Assert.assertEquals(batch.getCreatedAt().toLocalDate().format(DateTimeFormatter.ISO_DATE), request.getRequestUrl().queryParameter("date"));
    Assert.assertEquals(batch.getBatchName(), request.getRequestUrl().queryParameter("batchTag"));
    Assert.assertEquals(certificate.getThumbprint(), request.getHeader(efgsProperties.getCertAuth().getHeaderFields().getThumbprint()));

    Assert.assertEquals(0, callbackTaskRepository.count());
    Assert.assertEquals(1, callbackSubscriptionRepository.count());
  }

  @Test
  public void callbackExecutorShouldDeleteSubscriptionAfterMaxRetriesIsReached() throws InterruptedException, MalformedURLException {
    CallbackSubscriptionEntity subscription1 = createSubscription(TestData.CALLBACK_ID_FIRST);
    DiagnosisKeyBatchEntity batch1 = createDiagnosisKeyBatch("BT1", ZonedDateTime.now(ZoneOffset.UTC));
    CallbackTaskEntity task = createCallbackTask(subscription1, batch1, null);
    CertificateEntity certificate = insertCallbackCertificate();

    for (int i = 0; i <= efgsProperties.getCallback().getMaxRetries(); i++) {
      Assert.assertEquals(1, callbackTaskRepository.count());
      Assert.assertEquals(1, callbackSubscriptionRepository.count());

      mockWebServer.enqueue(new MockResponse().setResponseCode(400));
      callbackTaskExecutorService.execute();

      RecordedRequest request = mockWebServer.takeRequest();
      Assert.assertEquals(batch1.getCreatedAt().toLocalDate().format(DateTimeFormatter.ISO_DATE), request.getRequestUrl().queryParameter("date"));
      Assert.assertEquals(batch1.getBatchName(), request.getRequestUrl().queryParameter("batchTag"));
      Assert.assertEquals(certificate.getThumbprint(), request.getHeader(efgsProperties.getCertAuth().getHeaderFields().getThumbprint()));

      final int finalI = i;

      callbackTaskRepository.findById(task.getId()).ifPresent(t -> {
        Assert.assertEquals(finalI + 1, t.getRetries());
        Assert.assertNotNull(t.getLastTry());

        // modify lastTry property to skip wait time
        t.setLastTry(ZonedDateTime.now(ZoneOffset.UTC).minusSeconds(
          efgsProperties.getCallback().getRetryWait() + 60
        ));
        callbackTaskRepository.save(t);
      });
    }

    Assert.assertEquals(0, callbackTaskRepository.count());
    Assert.assertEquals(0, callbackSubscriptionRepository.count());
  }

  @Test
  public void callbackExecutorShouldProcessMultipleTasksInCorrectOrder() throws InterruptedException, MalformedURLException {
    CallbackSubscriptionEntity subscription1 = createSubscription(TestData.CALLBACK_ID_FIRST);
    DiagnosisKeyBatchEntity batch1 = createDiagnosisKeyBatch("BT1", ZonedDateTime.now(ZoneOffset.UTC));
    DiagnosisKeyBatchEntity batch2 = createDiagnosisKeyBatch("BT2", ZonedDateTime.now(ZoneOffset.UTC));
    DiagnosisKeyBatchEntity batch3 = createDiagnosisKeyBatch("BT3", ZonedDateTime.now(ZoneOffset.UTC));
    DiagnosisKeyBatchEntity batch4 = createDiagnosisKeyBatch("BT4", ZonedDateTime.now(ZoneOffset.UTC));
    CallbackTaskEntity task1 = createCallbackTask(subscription1, batch1, null);
    CallbackTaskEntity task2 = createCallbackTask(subscription1, batch2, task1);
    CallbackTaskEntity task3 = createCallbackTask(subscription1, batch3, task2);
    createCallbackTask(subscription1, batch4, task3);

    CertificateEntity certificate = insertCallbackCertificate();

    mockWebServer.enqueue(new MockResponse().setResponseCode(200));
    mockWebServer.enqueue(new MockResponse().setResponseCode(400));

    callbackTaskExecutorService.execute();

    RecordedRequest request = mockWebServer.takeRequest();
    Assert.assertEquals(batch1.getCreatedAt().toLocalDate().format(DateTimeFormatter.ISO_DATE), request.getRequestUrl().queryParameter("date"));
    Assert.assertEquals(batch1.getBatchName(), request.getRequestUrl().queryParameter("batchTag"));
    Assert.assertEquals(certificate.getThumbprint(), request.getHeader(efgsProperties.getCertAuth().getHeaderFields().getThumbprint()));

    request = mockWebServer.takeRequest();
    Assert.assertEquals(batch2.getCreatedAt().toLocalDate().format(DateTimeFormatter.ISO_DATE), request.getRequestUrl().queryParameter("date"));
    Assert.assertEquals(batch2.getBatchName(), request.getRequestUrl().queryParameter("batchTag"));
    Assert.assertEquals(certificate.getThumbprint(), request.getHeader(efgsProperties.getCertAuth().getHeaderFields().getThumbprint()));

    // checking if last try property is set
    task2 = callbackTaskRepository.findById(task2.getId()).get();
    Assert.assertNotNull(task2.getLastTry());
    Assert.assertNull(task2.getNotBefore());

    // second request failed --> no more callbacks should be executed until timeout is reached
    callbackTaskExecutorService.execute();
    Assert.assertEquals(2, mockWebServer.getRequestCount());

    // modify lastTry property to skip wait time
    task2.setLastTry(ZonedDateTime.now(ZoneOffset.UTC).minusSeconds(
      efgsProperties.getCallback().getRetryWait() + 60
    ));
    callbackTaskRepository.save(task2);

    mockWebServer.enqueue(new MockResponse().setResponseCode(200));
    mockWebServer.enqueue(new MockResponse().setResponseCode(200));
    mockWebServer.enqueue(new MockResponse().setResponseCode(200));

    callbackTaskExecutorService.execute();

    request = mockWebServer.takeRequest();
    Assert.assertEquals(batch2.getCreatedAt().toLocalDate().format(DateTimeFormatter.ISO_DATE), request.getRequestUrl().queryParameter("date"));
    Assert.assertEquals(batch2.getBatchName(), request.getRequestUrl().queryParameter("batchTag"));
    Assert.assertEquals(certificate.getThumbprint(), request.getHeader(efgsProperties.getCertAuth().getHeaderFields().getThumbprint()));

    request = mockWebServer.takeRequest();
    Assert.assertEquals(batch3.getCreatedAt().toLocalDate().format(DateTimeFormatter.ISO_DATE), request.getRequestUrl().queryParameter("date"));
    Assert.assertEquals(batch3.getBatchName(), request.getRequestUrl().queryParameter("batchTag"));
    Assert.assertEquals(certificate.getThumbprint(), request.getHeader(efgsProperties.getCertAuth().getHeaderFields().getThumbprint()));

    request = mockWebServer.takeRequest();
    Assert.assertEquals(batch4.getCreatedAt().toLocalDate().format(DateTimeFormatter.ISO_DATE), request.getRequestUrl().queryParameter("date"));
    Assert.assertEquals(batch4.getBatchName(), request.getRequestUrl().queryParameter("batchTag"));
    Assert.assertEquals(certificate.getThumbprint(), request.getHeader(efgsProperties.getCertAuth().getHeaderFields().getThumbprint()));

    Assert.assertEquals(0, callbackTaskRepository.count());
    Assert.assertEquals(1, callbackSubscriptionRepository.count());
  }

  @Test
  public void callbackExecutorShouldMarkTaskForRetryOnFailedRequest() throws MalformedURLException {
    CallbackSubscriptionEntity subscription1 = createSubscription(TestData.CALLBACK_ID_FIRST);
    DiagnosisKeyBatchEntity batch = createDiagnosisKeyBatch("BT1", ZonedDateTime.now(ZoneOffset.UTC));
    createCallbackTask(subscription1, batch, null);
    insertCallbackCertificate();

    mockWebServer.enqueue(new MockResponse().setResponseCode(404));

    callbackTaskExecutorService.execute();

    Assert.assertEquals(1, callbackTaskRepository.findAll().get(0).getRetries());
    Assert.assertNotNull(callbackTaskRepository.findAll().get(0).getLastTry());
    Assert.assertNull(callbackTaskRepository.findAll().get(0).getExecutionLock());

    Assert.assertEquals(1, callbackTaskRepository.count());
    Assert.assertEquals(1, callbackSubscriptionRepository.count());
  }

  @Test
  public void callbackExecutorShoulRemoveNotBeforeFromNextTaskOnSuccess() throws MalformedURLException {
    CallbackSubscriptionEntity subscription1 = createSubscription(TestData.CALLBACK_ID_FIRST);
    DiagnosisKeyBatchEntity batch = createDiagnosisKeyBatch("BT1", ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    DiagnosisKeyBatchEntity batch2 = createDiagnosisKeyBatch("BT2", ZonedDateTime.now(ZoneOffset.UTC));
    CallbackTaskEntity task1 = createCallbackTask(subscription1, batch, null);
    createCallbackTask(subscription1, batch2, task1);
    insertCallbackCertificate();

    mockWebServer.enqueue(new MockResponse().setResponseCode(200));
    mockWebServer.enqueue(new MockResponse().setResponseCode(400)); // let the second request fail to check if notBefore is removed

    callbackTaskExecutorService.execute();

    Assert.assertEquals(1, callbackTaskRepository.count());
    Assert.assertEquals(1, callbackSubscriptionRepository.count());

    Assert.assertNull(callbackTaskRepository.findAll().get(0).getNotBefore());
  }

  @Test
  public void callbackExecutorShoulNotRemoveNotBeforeFromNextTaskOnFailure() throws MalformedURLException {
    CallbackSubscriptionEntity subscription1 = createSubscription(TestData.CALLBACK_ID_FIRST);
    DiagnosisKeyBatchEntity batch = createDiagnosisKeyBatch("BT1", ZonedDateTime.now(ZoneOffset.UTC).minusMinutes(1));
    DiagnosisKeyBatchEntity batch2 = createDiagnosisKeyBatch("BT2", ZonedDateTime.now(ZoneOffset.UTC));
    CallbackTaskEntity task1 = createCallbackTask(subscription1, batch, null);
    createCallbackTask(subscription1, batch2, task1);
    insertCallbackCertificate();

    mockWebServer.enqueue(new MockResponse().setResponseCode(400));

    callbackTaskExecutorService.execute();

    Assert.assertEquals(2, callbackTaskRepository.count());
    Assert.assertEquals(1, callbackSubscriptionRepository.count());

    Assert.assertEquals(task1.getId(), callbackTaskRepository.findAll().get(1).getNotBefore().getId());
  }

  private CertificateEntity insertCallbackCertificate() throws MalformedURLException {
    CertificateEntity callbackCertificate = new CertificateEntity(
      null, ZonedDateTime.now(ZoneOffset.UTC), "thumbprint", TestData.COUNTRY_A, CertificateEntity.CertificateType.CALLBACK, false, "localhost"
    );

    return certificateRepository.save(callbackCertificate);
  }

  private DiagnosisKeyBatchEntity createDiagnosisKeyBatch(String batchTag, ZonedDateTime created_at) {
    DiagnosisKeyBatchEntity batch = new DiagnosisKeyBatchEntity(
      null, created_at, batchTag, null
    );

    return diagnosisKeyBatchRepository.save(batch);
  }

  private CallbackTaskEntity createCallbackTask(CallbackSubscriptionEntity subscription, DiagnosisKeyBatchEntity batch, CallbackTaskEntity notBefore) {
    CallbackTaskEntity task = new CallbackTaskEntity(
      null, ZonedDateTime.now(ZoneOffset.UTC), null, null, 0, notBefore, batch, subscription
    );

    return callbackTaskRepository.save(task);
  }

  private CallbackSubscriptionEntity createSubscription(String callbackId) {
    CallbackSubscriptionEntity subscription = new CallbackSubscriptionEntity(
      null, ZonedDateTime.now(ZoneOffset.UTC), callbackId, mockCallbackUrl, TestData.COUNTRY_A
    );

    return callbackSubscriptionRepository.save(subscription);
  }

}
