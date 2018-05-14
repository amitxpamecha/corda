package com.template.ledger.flow;

import net.corda.core.flows.*;
import net.corda.core.transactions.SignedTransaction;
import net.corda.core.utilities.ProgressTracker;
import net.corda.core.utilities.ProgressTracker.Step;

import static net.corda.core.contracts.ContractsDSL.requireThat;

@InitiatedBy(SellerFlow.class)
public class BuyerFlow extends FlowLogic<SignedTransaction> {

    private final FlowSession counterpartySession;

    public BuyerFlow(FlowSession counterpartySession) {
        this.counterpartySession = counterpartySession;
    }

    private static final Step RECEIVING_AND_SENDING_DATA = new Step("Sending data between parties.");
    private static final Step SIGNING = new Step("Responding to CollectSignaturesFlow.");
    private static final Step FINALISATION = new Step("Finalising a transaction.");

    private final ProgressTracker progressTracker = new ProgressTracker(
            RECEIVING_AND_SENDING_DATA,
            SIGNING,
            FINALISATION
    );

    @Override
    public SignedTransaction call() throws FlowException {

        //STAGE-1 - RECEIVING_AND_SENDING_DATA
        receiveAndSendData();

        //STAGE-2 - SIGNING
        signTransaction();

        //STAGE-3 - FINALISATION
        finalizeTransaction();
        return null;
    }

    private void receiveAndSendData() throws FlowException {
        progressTracker.setCurrentStep(RECEIVING_AND_SENDING_DATA);

        Object obj = counterpartySession.receive(Object.class).unwrap(data -> data);
        counterpartySession.send(true);
    }

    private void signTransaction() throws FlowException {
        progressTracker.setCurrentStep(SIGNING);
        class SignTxFlow extends SignTransactionFlow {
            private SignTxFlow(FlowSession otherSession, ProgressTracker progressTracker) {
                super(otherSession, progressTracker);
            }

            @Override
            protected void checkTransaction(SignedTransaction stx) {
                requireThat(require -> {
                    // Any additional checking we see fit...
                    //CommodityContract.State outputState = (CommodityContract.State) stx.getTx().getOutputs().get(0).getData();
                    //require.using("This must be an Commodity transaction.", outputState instanceof CommodityContract.State);
                    //require.using("Commodity with a value over 100.", outputState.getAmount() <= 100);
                    return null;
                });
            }
        }

        subFlow(new SignTxFlow(counterpartySession, SignTransactionFlow.tracker()));

    }

    private void finalizeTransaction(){
        progressTracker.setCurrentStep(FINALISATION);
    }
}
