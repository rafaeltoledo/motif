package testcases.T070_scope_factory_scope_extends_dependencies;

import motif.Scope;
import motif.ScopeFactory;

@Scope
public interface BarScope {

    String string();

    @motif.Objects
    class Objects {

        String string(Integer integer) {
            return integer.toString();
        }
    }

    interface Dependencies {

        Integer integer();
    }

    class Factory extends ScopeFactory<BarScope, Dependencies> {}
}
