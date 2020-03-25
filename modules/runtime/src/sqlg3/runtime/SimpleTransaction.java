package sqlg3.runtime;

import sqlg3.core.IDBCommon;
import sqlg3.core.ISimpleTransaction;

public final class SimpleTransaction implements ISimpleTransaction {

    private final TransactionContext transaction;

    public SimpleTransaction(GlobalContext global, ConnectionManager cman) {
        this.transaction = new TransactionContext(global, cman);
    }

    @Override
    public <T extends IDBCommon> T getInterface(Class<T> iface) {
        return transaction.getInterface(iface, true);
    }
}
