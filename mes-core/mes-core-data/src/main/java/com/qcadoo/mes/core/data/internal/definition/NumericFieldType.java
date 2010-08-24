package com.qcadoo.mes.core.data.internal.definition;

import java.math.BigDecimal;

import com.qcadoo.mes.core.data.definition.FieldType;

public final class NumericFieldType implements FieldType {

    private final int precision;

    private final int scale;

    private final long maxValue;

    public NumericFieldType(final int scale, final int precision) {
        this.scale = scale;
        this.precision = precision;
        this.maxValue = (long) Math.pow(10, scale) - 1;
    }

    @Override
    public boolean isSearchable() {
        return true;
    }

    @Override
    public boolean isOrderable() {
        return true;
    }

    @Override
    public boolean isAggregable() {
        return true;
    }

    @Override
    public boolean isValidType(final Object value) {
        if (!(value instanceof Number)) {
            return false;
        }
        if (precision == 0) {
            if (value instanceof Float) {
                return false;
            }
            if (value instanceof Double) {
                return false;
            }
            if (value instanceof BigDecimal) {
                return false;
            }
        }
        if (((Number) value).longValue() > maxValue) {
            return false;
        }
        return true;
    }

    @Override
    public int getNumericType() {
        if (precision == 0) {
            return NUMERIC_TYPE_INTEGER;
        } else {
            return NUMERIC_TYPE_DECIMAL;
        }
    }

}
