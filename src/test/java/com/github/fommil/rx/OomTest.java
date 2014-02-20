package com.github.fommil.rx;

import org.junit.Test;
import rx.Observable;
import rx.Subscriber;
import rx.functions.Func1;

import java.util.concurrent.atomic.AtomicLong;

import static rx.Observable.OnSubscribe;
import static rx.Observable.create;


public class OomTest {

    private Observable<Long[]> ob = create(new OnSubscribe<Long[]>() {
        @Override
        public void call(Subscriber<? super Long[]> subscriber) {
            while (!subscriber.isUnsubscribed()) {
                subscriber.onNext(new Long[10 * 1024 * 1024]);
            }
            subscriber.onCompleted();
        }
    });

    static class IntSubscriber extends Subscriber<Long[]> {
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
        public void onNext(Long[] next) {
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
        Observable<Long[]> parallel = ob.parallel(new Func1<Observable<Long[]>, Observable<Long[]>>() {
            @Override
            public Observable<Long[]> call(Observable<Long[]> integerObservable) {
                return integerObservable;
            }
        });
        parallel.subscribe(new IntSubscriber(1000));
    }

}
