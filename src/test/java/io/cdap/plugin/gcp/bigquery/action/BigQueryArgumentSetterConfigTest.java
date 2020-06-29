package io.cdap.plugin.gcp.bigquery.action;

import io.cdap.cdap.etl.api.validation.CauseAttributes;
import io.cdap.cdap.etl.api.validation.ValidationException;
import io.cdap.cdap.etl.api.validation.ValidationFailure;
import io.cdap.cdap.etl.mock.validation.MockFailureCollector;
import io.cdap.plugin.common.Constants;
import io.cdap.plugin.gcp.bigtable.sink.BigtableSinkConfig;
import io.cdap.plugin.gcp.bigtable.sink.BigtableSinkConfigBuilder;
import org.junit.Assert;
import org.junit.Test;


public class BigQueryArgumentSetterConfigTest {

    private static final String VALID_REF = "ref";
    private static final String VALID_DATASET = "dataset";
    private static final String VALID_TABLE = "table";
    private static final String VALID_ARGUMENT_SELECTION_CONDITIONS="feed=10;id=0";
    private static final String VALID_ARGUMENT_COLUMN="name";

    @Test
    public void testValidateMissingArgumentSelectionConditions() {
        BigQueryArgumentSetterConfig config = getBuilder()
                .setArgumentSelectionConditions(null)
                .build();

        validateConfigValidationFail(config, BigQueryArgumentSetterConfig.NAME_ARGUMENT_SELECTION_CONDITIONS);
    }

    @Test
    public void testValidateMissingArgumentColumns() {
        BigQueryArgumentSetterConfig config = getBuilder()
                .setArgumentsColumns(null)
                .build();

        validateConfigValidationFail(config, BigQueryArgumentSetterConfig.NAME_ARGUMENTS_COLUMNS);
    }

    private static BigQueryArgumentSetterConfigBuilder getBuilder() {
        return BigQueryArgumentSetterConfigBuilder.BigQueryArgumentSetterConfig()
                .setReferenceName(VALID_REF)
                .setDataset(VALID_DATASET)
                .setTable(VALID_TABLE)
                .setArgumentSelectionConditions(VALID_ARGUMENT_SELECTION_CONDITIONS)
                .setArgumentsColumns(VALID_ARGUMENT_COLUMN)
                ;
    }

    private static void validateConfigValidationFail(BigQueryArgumentSetterConfig config, String propertyValue) {
        MockFailureCollector collector = new MockFailureCollector();
        ValidationFailure failure;
        try {
            config.validateProperties(collector);
            Assert.assertEquals(1, collector.getValidationFailures().size());
//            failure = collector.getValidationFailures().get(0);
        } catch (ValidationException e) {
            // it is possible that validation exception was thrown during validation. so catch the exception
            Assert.assertEquals(1, e.getFailures().size());
            failure = e.getFailures().get(0);
        }

//        Assert.assertEquals(propertyValue, failure.getCauses().get(0).getAttribute(CauseAttributes.STAGE_CONFIG));
    }
}
