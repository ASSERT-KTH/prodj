package se.kth.castor.rockstofetch.util;

import java.util.concurrent.locks.Lock;

public class ClosableLock {

  private final Lock underlying;

  public ClosableLock(Lock underlying) {
    this.underlying = underlying;
  }

  public UnexceptionalAutoClosable lock() {
    return new LockGuard();
  }

  public interface UnexceptionalAutoClosable extends AutoCloseable {

    @Override
    void close();

  }

  private class LockGuard implements UnexceptionalAutoClosable {

    public LockGuard() {
      underlying.lock();
    }

    @Override
    public void close() {
      underlying.unlock();
    }
  }
}
