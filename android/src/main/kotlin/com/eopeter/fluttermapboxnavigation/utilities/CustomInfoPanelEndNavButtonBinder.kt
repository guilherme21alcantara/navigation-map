package com.eopeter.fluttermapboxnavigation.utilities

import android.app.Activity
import android.view.ViewGroup
import com.mapbox.navigation.core.MapboxNavigation
import com.mapbox.navigation.core.lifecycle.MapboxNavigationObserver
import com.mapbox.navigation.ui.base.lifecycle.UIBinder
import com.mapbox.navigation.ui.base.lifecycle.UIComponent

class CustomInfoPanelEndNavButtonBinder(
    private val activity: Activity
) : UIBinder {

    override fun bind(viewGroup: ViewGroup): MapboxNavigationObserver {
        // Remove qualquer botão ou view anterior
        viewGroup.removeAllViews()

        // Não adiciona nenhum botão — painel ficará vazio

        return object : UIComponent() {
            override fun onAttached(mapboxNavigation: MapboxNavigation) {
                super.onAttached(mapboxNavigation)
                // Nenhuma ação de clique, já que não há botão
            }
        }
    }
}