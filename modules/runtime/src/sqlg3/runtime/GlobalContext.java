package sqlg3.runtime;

import sqlg3.core.MetaColumn;
import sqlg3.core.SQLGException;
import sqlg3.core.SQLGLogger;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public final class GlobalContext {

    public final SQLGLogger logger;
    final DBSpecific db;
    final TypeMappers mappers;
    final SqlTrace trace;

    public volatile boolean checkRowTypes = false;

    private final ConcurrentMap<Class<?>, RowTypeFactory<?>> rowTypeFactoryMap = new ConcurrentHashMap<>();

    public GlobalContext(SQLGLogger logger, DBSpecific db, TypeMappers mappers, SqlTrace trace) {
        this.logger = logger;
        this.db = db;
        this.mappers = mappers;
        this.trace = trace;
    }

    public GlobalContext(SQLGLogger logger, DBSpecific db, TypeMappers mappers) {
        this(logger, db, mappers, SqlTrace.createDefault(logger));
    }

    private static <T> int fetchParameter(TypeMappers mappers, Class<T> parameterType, ResultSet rs, int index,
                                          Object[] params, int i) throws SQLException {
        TypeMapper<T> mapper = mappers.getMapper(parameterType);
        params[i] = mapper.fetch(rs, index);
        return mapper.getResultSetColumns();
    }

    private static void checkRowType(ResultSetMetaData rsmd, Constructor<?> constructor) throws SQLException {
        int columnCount = rsmd.getColumnCount();
        int parameterCount = constructor.getParameterCount();
        if (columnCount != parameterCount) {
            throw new SQLGException(
                "Different number of columns in query (" + columnCount + ") and constructor (" + parameterCount + ")"
            );
        }
    }

    private static RowTypeFactory<?> createRowTypeFactory(Class<?> rowType, boolean meta, boolean check) {
        Constructor<?>[] constructors = rowType.getConstructors();
        if (constructors.length != 1)
            throw new SQLGException("Should be only one constructor for " + rowType.getCanonicalName());
        Constructor<?> constructor = constructors[0];
        Class<?>[] parameterTypes = constructor.getParameterTypes();
        return (mappers, rs) -> {
            Object[] params = new Object[parameterTypes.length];
            if (meta) {
                ResultSetMetaData rsmd = rs.getMetaData();
                if (check) {
                    checkRowType(rsmd, constructor);
                }
                for (int i = 0; i < parameterTypes.length; i++) {
                    if (!MetaColumn.class.equals(parameterTypes[i]))
                        throw new SQLGException("Meta row type should contain only MetaColumns");
                    int index = i + 1;
                    params[i] = new MetaColumn(
                        rsmd.isNullable(index) == ResultSetMetaData.columnNoNulls,
                        rsmd.getColumnDisplaySize(index), rsmd.getPrecision(index), rsmd.getScale(index)
                    );
                }
            } else {
                if (check) {
                    checkRowType(rs.getMetaData(), constructor);
                }
                int index = 1;
                for (int i = 0; i < parameterTypes.length; i++) {
                    index += fetchParameter(mappers, parameterTypes[i], rs, index, params, i);
                }
            }
            try {
                return constructor.newInstance(params);
            } catch (InstantiationException | IllegalAccessException | InvocationTargetException ex) {
                throw new SQLGException("Cannot invoke row constructor", ex);
            }
        };
    }

    @SuppressWarnings("unchecked")
    <T> RowTypeFactory<T> getRowTypeFactory(Class<T> rowType, boolean meta) {
        return (RowTypeFactory<T>) rowTypeFactoryMap.computeIfAbsent(rowType, c -> createRowTypeFactory(c, meta, checkRowTypes));
    }
}
