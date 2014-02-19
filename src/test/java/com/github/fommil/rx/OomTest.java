package com.github.fommil.rx;

import org.junit.Test;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Func1;

import java.util.Random;
import java.util.concurrent.atomic.AtomicLong;

import static rx.Observable.OnSubscribe;
import static rx.Observable.create;


/**
 * Must be run with a ridiculously low heap size, e.g.
 * {@code -Xmx5m}.
 */
public class OomTest {

    private Observable<Integer> ob = create(new OnSubscribe<Integer>() {
        @Override
        public void call(Subscriber<? super Integer> subscriber) {
            Random random = new Random();
            while (!subscriber.isUnsubscribed()) {
                subscriber.onNext(random.nextInt());
            }
            subscriber.onCompleted();
        }
    });

    static class IntSubscriber extends Subscriber<Integer> {
        private final AtomicLong count = new AtomicLong();
        private final long delay;

        public IntSubscriber(long delay) {
            this.delay = delay;
        }

        @Override
        public void onCompleted() {
        }

        @Override
        public void onError(Throwable e) {
        }

        @Override
        public void onNext(Integer integer) {
            long seen = count.getAndIncrement();
            if (seen > 0 && seen % 1000 == 0) {
                System.out.println("seen " + seen);
                unsubscribe();
            }
            if (delay > 0)
            try {
                Thread.sleep(delay);
            } catch (InterruptedException e) {
            }
        }
    };

    @Test
    public void testInfiniteSource() throws Exception {
        // increasing the delay makes no difference here (except to slow down the test)
        ob.subscribe(new IntSubscriber(0));
    }

    @Test
    public void testInfiniteSourceWithParallel() throws Exception {
        Observable<Integer> parallel = ob.parallel(new Func1<Observable<Integer>, Observable<Integer>>() {
            @Override
            public Observable<Integer> call(Observable<Integer> integerObservable) {
                return integerObservable;
            }
        });
        parallel.subscribe(new IntSubscriber(1000));
    }

}
