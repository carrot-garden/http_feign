/*
 * Copyright 2013 Netflix, Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package feign.rxjava;

import feign.Feign;
import feign.IncrementalCallback;
import feign.RequestLine;
import feign.gson.GsonModule;
import org.testng.annotations.Test;
import rx.Observable;
import rx.Observer;
import rx.Subscription;
import rx.util.functions.Func1;

import javax.inject.Named;
import java.util.concurrent.CountDownLatch;

/**
 * Explores the interactions between Feign and RxJava.
 */
@Test
public class RxJavaLearningTest {

  interface GitHub {
    @RequestLine("GET /repos/{owner}/{repo}/contributors")
    void contributors(@Named("owner") String owner, @Named("repo") String repo,
                      IncrementalCallback<Contributor> contributors);
  }

  static class Contributor {
    String login;
    int contributions;
  }

  public static void main(String... args) throws InterruptedException {
    GitHub github = Feign.create(GitHub.class, "https://api.github.com", new GsonModule());

    System.out.println("With Observable, we can react to our contributors!");
    Observable<Contributor> contributors = new GitHubContributorObservable(github, "netflix", "feign");

    final CountDownLatch latch = new CountDownLatch(1);

    System.out.println("Our Observer is interested in the count of contributors.");
    Observer<Contributor> observer = new Observer<Contributor>() {

      public int count;

      @Override public void onCompleted() {
        System.out.println("found " + count + " contributors");
        latch.countDown();
      }

      @Override public void onError(Exception cause) {
        cause.printStackTrace();
        latch.countDown();
      }

      // parsed directly from the text stream without an intermediate collection.
      @Override public void onNext(Contributor contributor) {
        System.out.println(contributor.login + " (" + contributor.contributions + ")");
        count++;
      }
    };

    System.out.println("Let's subscribe and see what the current count is!");
    contributors.subscribe(observer);

    System.out.println("Waiting for the observer to finish processing");
    latch.await();

    System.exit(0);
  }

  static class GitHubContributorObservable extends Observable<Contributor> {

    public GitHubContributorObservable(GitHub github, String owner, String repo) {
      super(new ContributorFunction(github, owner, repo));
    }

    static class ContributorFunction extends ObserverFunction<Contributor> {
      private final GitHub github;
      private final String owner;
      private final String repo;

      ContributorFunction(GitHub github, String owner, String repo) {
        this.github = github;
        this.owner = owner;
        this.repo = repo;
      }

      @Override void subscribe(IncrementalCallback<Contributor> callback) {
        github.contributors(owner, repo, callback);
      }
    }
  }

  static abstract class ObserverFunction<T> implements Func1<Observer<T>, Subscription> {

    @Override public Subscription call(Observer<T> observer) {
      IncrementalCallbackObserverAdapter<T> subscription = new IncrementalCallbackObserverAdapter<T>(observer);
      subscribe(subscription);
      return subscription;
    }

    abstract void subscribe(IncrementalCallback<T> callback);
  }

  static class IncrementalCallbackObserverAdapter<T> implements IncrementalCallback<T>, Subscription {
    private final Observer<T> delegate;
    private boolean subscribed = true;

    IncrementalCallbackObserverAdapter(Observer<T> delegate) {
      this.delegate = delegate;
    }

    @Override public void unsubscribe() {
      subscribed = false;
    }

    @Override public void onNext(T element) {
      if (subscribed)
        delegate.onNext(element);
    }

    @Override public void onSuccess() {
      delegate.onCompleted();
    }

    @Override public void onFailure(Throwable cause) {
      if (cause instanceof Exception)
        delegate.onError(Exception.class.cast(cause));
    }
  }
}
